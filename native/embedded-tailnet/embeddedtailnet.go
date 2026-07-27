package embeddedtailnet

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"go4.org/netipx"
	"tailscale.com/envknob"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

type Engine struct {
	mu          sync.Mutex
	networkMu   sync.RWMutex
	interfaces  []netmon.Interface
	networkKey  string
	server      *tsnet.Server
	tunnels     map[string]*tunnel
	destination string
}

func (e *Engine) Start(stateDir string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.server != nil {
		return engineStatus(e.server, e.destination)
	}
	if stateDir == "" {
		return "", errors.New("state directory is required")
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return "", err
	}
	if err := os.Chmod(stateDir, 0700); err != nil {
		return "", err
	}
	netmon.RegisterInterfaceGetter(e.networkInterfaces)
	envknob.SetNoLogsNoSupport()
	envknob.Setenv("TS_LOGS_DIR", stateDir)
	server := &tsnet.Server{
		Dir:      stateDir,
		Hostname: "dealer-fold6",
		UserLogf: func(string, ...any) {},
	}
	if err := server.Start(); err != nil {
		return "", err
	}
	e.server = server
	e.tunnels = make(map[string]*tunnel)
	current, err := engineStatus(server, "")
	if err != nil {
		_ = server.Close()
		e.server = nil
	}
	return current, err
}

func (e *Engine) SetNetwork(interfaceName, addressesJSON, gateway string) error {
	var addresses []string
	if err := json.Unmarshal([]byte(addressesJSON), &addresses); err != nil {
		return fmt.Errorf("invalid network addresses: %w", err)
	}
	if len(addresses) > 64 {
		return errors.New("too many network addresses")
	}
	if interfaceName == "" && len(addresses) > 0 {
		return errors.New("network interface is required")
	}
	var interfaces []netmon.Interface
	if interfaceName != "" {
		current := netmon.Interface{
			Interface: &net.Interface{Name: interfaceName, Flags: net.FlagUp},
		}
		for _, value := range addresses {
			prefix, err := netip.ParsePrefix(value)
			if err != nil {
				return fmt.Errorf("invalid network prefix %q: %w", value, err)
			}
			current.AltAddrs = append(current.AltAddrs, netipx.PrefixIPNet(prefix))
		}
		interfaces = append(interfaces, current)
	}
	if gateway != "" {
		if _, err := netip.ParseAddr(gateway); err != nil {
			return fmt.Errorf("invalid network gateway %q: %w", gateway, err)
		}
	}
	key := interfaceName + "\x00" + gateway + "\x00" + strings.Join(addresses, "\x00")
	e.networkMu.Lock()
	changed := e.networkKey != key
	e.interfaces = interfaces
	e.networkKey = key
	e.networkMu.Unlock()
	if !changed {
		return nil
	}
	updateNetworkRoute(interfaceName, gateway)
	e.mu.Lock()
	server := e.server
	e.mu.Unlock()
	if server != nil {
		server.Sys().NetMon.Get().InjectEvent()
	}
	return nil
}

func (e *Engine) networkInterfaces() ([]netmon.Interface, error) {
	e.networkMu.RLock()
	defer e.networkMu.RUnlock()
	return append([]netmon.Interface(nil), e.interfaces...), nil
}

func (e *Engine) Status() (string, error) {
	e.mu.Lock()
	server := e.server
	destination := e.destination
	e.mu.Unlock()
	if server == nil {
		return `{"state":"stopped"}`, nil
	}
	return engineStatus(server, destination)
}

func (e *Engine) Stop() error {
	e.mu.Lock()
	server := e.server
	e.server = nil
	tunnels := e.tunnels
	e.tunnels = nil
	e.destination = ""
	e.mu.Unlock()
	for _, tunnel := range tunnels {
		tunnel.close()
	}
	if server == nil {
		return nil
	}
	return server.Close()
}

func (e *Engine) Reset(stateDir string) error {
	return resetState(stateDir, e.Stop, os.RemoveAll)
}

func resetState(stateDir string, stop func() error, remove func(string) error) error {
	clean := filepath.Clean(stateDir)
	if !filepath.IsAbs(clean) || filepath.Base(clean) != "embedded-tailnet" {
		return errors.New("invalid embedded tailnet state directory")
	}
	if err := stop(); err != nil {
		return err
	}
	return remove(clean)
}

func (e *Engine) OpenTunnel(destination string, port int64) (string, error) {
	if destination == "" {
		return "", errors.New("tunnel destination is required")
	}
	if port < 1 || port > 65535 {
		return "", fmt.Errorf("invalid tunnel port %d", port)
	}
	e.mu.Lock()
	server := e.server
	e.mu.Unlock()
	if server == nil {
		return "", errors.New("embedded tailnet is not started")
	}
	target := net.JoinHostPort(destination, strconv.FormatInt(port, 10))
	current, err := newTunnel(target, server.Dial)
	if err != nil {
		return "", err
	}
	e.mu.Lock()
	if e.server != server {
		e.mu.Unlock()
		go current.serve()
		current.close()
		return "", errors.New("embedded tailnet stopped while opening tunnel")
	}
	e.tunnels[current.id] = current
	e.destination = destination
	go func() {
		current.serve()
		e.mu.Lock()
		if e.tunnels[current.id] == current {
			delete(e.tunnels, current.id)
		}
		e.mu.Unlock()
	}()
	e.mu.Unlock()
	return current.descriptor()
}

func (e *Engine) CloseTunnel(id string) error {
	e.mu.Lock()
	current := e.tunnels[id]
	delete(e.tunnels, id)
	e.mu.Unlock()
	if current == nil {
		return nil
	}
	current.close()
	return nil
}

type dialContext func(context.Context, string, string) (net.Conn, error)

type tunnel struct {
	id       string
	token    string
	target   string
	listener net.Listener
	dial     dialContext
	ctx      context.Context
	cancel   context.CancelFunc
	done     chan struct{}
}

func newTunnel(target string, dial dialContext) (*tunnel, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	id, err := randomHex(16)
	if err != nil {
		_ = listener.Close()
		return nil, err
	}
	token, err := randomHex(32)
	if err != nil {
		_ = listener.Close()
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &tunnel{
		id:       id,
		token:    token,
		target:   target,
		listener: listener,
		dial:     dial,
		ctx:      ctx,
		cancel:   cancel,
		done:     make(chan struct{}),
	}, nil
}

func (t *tunnel) descriptor() (string, error) {
	port := t.listener.Addr().(*net.TCPAddr).Port
	encoded, err := json.Marshal(struct {
		ID    string `json:"id"`
		Port  int    `json:"port"`
		Token string `json:"token"`
	}{
		ID:    t.id,
		Port:  port,
		Token: t.token,
	})
	return string(encoded), err
}

func (t *tunnel) serve() {
	defer close(t.done)
	defer t.listener.Close()
	for {
		local, err := t.listener.Accept()
		if err != nil {
			return
		}
		authenticated := make(chan struct{})
		go func() {
			select {
			case <-t.ctx.Done():
				_ = local.Close()
			case <-authenticated:
			}
		}()
		authorized := t.authenticate(local)
		close(authenticated)
		if !authorized {
			_ = local.Close()
			continue
		}
		_ = t.listener.Close()
		remote, err := t.dial(t.ctx, "tcp", t.target)
		if err != nil {
			_, _ = fmt.Fprintf(local, "ERROR %s\n", err)
			_ = local.Close()
			return
		}
		_, _ = io.WriteString(local, "OK\n")
		go func() {
			<-t.ctx.Done()
			_ = local.Close()
			_ = remote.Close()
		}()
		copied := make(chan struct{}, 2)
		go func() { _, _ = io.Copy(remote, local); copied <- struct{}{} }()
		go func() { _, _ = io.Copy(local, remote); copied <- struct{}{} }()
		<-copied
		t.cancel()
		<-copied
		return
	}
}

func (t *tunnel) authenticate(connection net.Conn) bool {
	_ = connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	provided, err := bufio.NewReader(io.LimitReader(connection, 129)).ReadString('\n')
	_ = connection.SetReadDeadline(time.Time{})
	if err != nil {
		return false
	}
	provided = strings.TrimSuffix(provided, "\n")
	return len(provided) == len(t.token) &&
		subtle.ConstantTimeCompare([]byte(provided), []byte(t.token)) == 1
}

func (t *tunnel) close() {
	t.cancel()
	_ = t.listener.Close()
	<-t.done
}

func randomHex(bytes int) (string, error) {
	value := make([]byte, bytes)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return hex.EncodeToString(value), nil
}

func engineStatus(server *tsnet.Server, destination string) (string, error) {
	client, err := server.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	current, err := client.Status(ctx)
	if err != nil {
		return "", err
	}
	return encodeStatus(current, destination)
}

func encodeStatus(current *ipnstate.Status, destination string) (string, error) {
	state := "unavailable"
	var statusHealth string
	switch strings.ToLower(current.BackendState) {
	case "needslogin":
		state = "login_required"
	case "needsmachineauth":
		state = "unavailable"
		statusHealth = "Tailnet node requires administrator approval; approve it in the tailnet admin console"
	case "running":
		switch {
		case current.Self == nil || !current.Self.Online:
			state = "unavailable"
			statusHealth = "Tailnet node is offline; check the active network and retry"
		case len(current.Health) > 0:
			state = "degraded"
		default:
			state = "connected"
		}
	case "starting", "nostate":
		state = "starting"
	default:
		state = "unavailable"
		statusHealth = "Tailnet backend is unavailable; restart the embedded tailnet"
	}
	nodeName := ""
	if current.Self != nil {
		nodeName = current.Self.HostName
	}
	path, relay := peerPath(current, destination)
	health := append([]string(nil), current.Health...)
	if statusHealth != "" {
		health = append(health, statusHealth)
	}
	if path == "relayed" && state == "connected" {
		state = "degraded"
		health = append(append([]string(nil), health...), "Workstation path uses DERP; direct connectivity is unavailable")
	}
	encoded, err := json.Marshal(struct {
		State    string   `json:"state"`
		LoginURL string   `json:"loginUrl,omitempty"`
		NodeName string   `json:"nodeName,omitempty"`
		Path     string   `json:"path,omitempty"`
		Relay    string   `json:"relay,omitempty"`
		Health   []string `json:"health,omitempty"`
	}{
		State:    state,
		LoginURL: current.AuthURL,
		NodeName: nodeName,
		Path:     path,
		Relay:    relay,
		Health:   health,
	})
	return string(encoded), err
}

func peerPath(current *ipnstate.Status, destination string) (path, relay string) {
	destination = strings.TrimSuffix(strings.ToLower(destination), ".")
	if destination == "" {
		return "", ""
	}
	for _, peer := range current.Peer {
		dnsName := strings.TrimSuffix(strings.ToLower(peer.DNSName), ".")
		if dnsName != destination && !strings.EqualFold(peer.HostName, destination) {
			continue
		}
		if !peer.Active {
			return "", ""
		}
		if peer.CurAddr != "" {
			return "direct", ""
		}
		if peer.Relay != "" {
			return "relayed", peer.Relay
		}
		return "", ""
	}
	return "", ""
}
