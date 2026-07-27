package embeddedtailnet

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

type Engine struct {
	mu     sync.Mutex
	server *tsnet.Server
}

func (e *Engine) Start(stateDir string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.server != nil {
		return engineStatus(e.server)
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
	envknob.SetNoLogsNoSupport()
	server := &tsnet.Server{
		Dir:      stateDir,
		Hostname: "dealer-fold6",
		UserLogf: func(string, ...any) {},
	}
	if err := server.Start(); err != nil {
		return "", err
	}
	e.server = server
	current, err := engineStatus(server)
	if err != nil {
		_ = server.Close()
		e.server = nil
	}
	return current, err
}

func (e *Engine) Status() (string, error) {
	e.mu.Lock()
	server := e.server
	e.mu.Unlock()
	if server == nil {
		return `{"state":"stopped"}`, nil
	}
	return engineStatus(server)
}

func (e *Engine) Stop() error {
	e.mu.Lock()
	server := e.server
	e.server = nil
	e.mu.Unlock()
	if server == nil {
		return nil
	}
	return server.Close()
}

func engineStatus(server *tsnet.Server) (string, error) {
	client, err := server.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	current, err := client.StatusWithoutPeers(ctx)
	if err != nil {
		return "", err
	}
	return encodeStatus(current)
}

func encodeStatus(current *ipnstate.Status) (string, error) {
	state := "unavailable"
	switch strings.ToLower(current.BackendState) {
	case "needslogin":
		state = "login_required"
	case "running":
		switch {
		case current.Self == nil || !current.Self.Online:
			state = "unavailable"
		case len(current.Health) > 0:
			state = "degraded"
		default:
			state = "connected"
		}
	case "starting", "nostate":
		state = "starting"
	default:
		state = "unavailable"
	}
	nodeName := ""
	if current.Self != nil {
		nodeName = current.Self.HostName
	}
	encoded, err := json.Marshal(struct {
		State    string   `json:"state"`
		LoginURL string   `json:"loginUrl,omitempty"`
		NodeName string   `json:"nodeName,omitempty"`
		Health   []string `json:"health,omitempty"`
	}{
		State:    state,
		LoginURL: current.AuthURL,
		NodeName: nodeName,
		Health:   current.Health,
	})
	return string(encoded), err
}
