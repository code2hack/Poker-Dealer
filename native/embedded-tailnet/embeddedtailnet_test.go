package embeddedtailnet

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func TestEngineRejectsMissingPrivateStateDirectory(t *testing.T) {
	var engine Engine
	if _, err := engine.Start(""); err == nil {
		t.Fatal("Start accepted an empty state directory")
	}
	if err := engine.Stop(); err != nil {
		t.Fatal(err)
	}
}

func TestStatusReportsOnlyEstablishedConnectivityAsConnected(t *testing.T) {
	status := &ipnstate.Status{
		BackendState: "Running",
		Self: &ipnstate.PeerStatus{
			HostName: "dealer-fold6",
			Online:   true,
		},
	}
	got, err := encodeStatus(status, "")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"connected","nodeName":"dealer-fold6"}`; got != want {
		t.Fatalf("status = %s, want %s", got, want)
	}

	status.Health = []string{"relay-only"}
	got, err = encodeStatus(status, "")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"degraded","nodeName":"dealer-fold6","health":["relay-only"]}`; got != want {
		t.Fatalf("status = %s, want %s", got, want)
	}

	status.Health = nil
	status.Self.Online = false
	got, err = encodeStatus(status, "")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"unavailable","nodeName":"dealer-fold6","health":["Tailnet node is offline; check the active network and retry"]}`; got != want {
		t.Fatalf("status = %s, want %s", got, want)
	}

	status.Health = []string{"control connection unavailable"}
	got, err = encodeStatus(status, "")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"unavailable","nodeName":"dealer-fold6","health":["control connection unavailable","Tailnet node is offline; check the active network and retry"]}`; got != want {
		t.Fatalf("offline status = %s, want %s", got, want)
	}

	status.BackendState = "NeedsMachineAuth"
	status.Health = nil
	got, err = encodeStatus(status, "")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"unavailable","nodeName":"dealer-fold6","health":["Tailnet node requires administrator approval; approve it in the tailnet admin console"]}`; got != want {
		t.Fatalf("machine approval status = %s, want %s", got, want)
	}
}

func TestStatusReportsDirectAndRelayedPeerPaths(t *testing.T) {
	status := &ipnstate.Status{
		BackendState: "Running",
		Self:         &ipnstate.PeerStatus{HostName: "dealer-fold6", Online: true},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			{}: {
				DNSName: "u4090.example.ts.net.",
				Active:  true,
				CurAddr: "192.0.2.1:41641",
				Relay:   "hkg",
			},
		},
	}
	got, err := encodeStatus(status, "u4090.example.ts.net")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"connected","nodeName":"dealer-fold6","path":"direct"}`; got != want {
		t.Fatalf("direct status = %s, want %s", got, want)
	}

	for _, peer := range status.Peer {
		peer.CurAddr = ""
	}
	got, err = encodeStatus(status, "u4090.example.ts.net")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"degraded","nodeName":"dealer-fold6","path":"relayed","relay":"hkg","health":["Workstation path uses DERP; direct connectivity is unavailable"]}`; got != want {
		t.Fatalf("relayed status = %s, want %s", got, want)
	}

	for _, peer := range status.Peer {
		peer.Relay = ""
		peer.PeerRelay = "192.0.2.20:1234:1"
	}
	got, err = encodeStatus(status, "u4090.example.ts.net")
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"connected","nodeName":"dealer-fold6"}`; got != want {
		t.Fatalf("peer-relay status = %s, want %s", got, want)
	}
}

func TestAndroidNetworkSnapshotSuppliesInterfacesWithoutNetlink(t *testing.T) {
	var engine Engine
	if err := engine.SetNetwork(
		"rmnet_data0",
		`["192.0.2.10/24","2001:db8::1/64"]`,
		"192.0.2.1",
	); err != nil {
		t.Fatal(err)
	}
	interfaces, err := engine.networkInterfaces()
	if err != nil {
		t.Fatal(err)
	}
	if len(interfaces) != 1 || interfaces[0].Name != "rmnet_data0" {
		t.Fatalf("interfaces = %#v", interfaces)
	}
	if got := len(interfaces[0].AltAddrs); got != 2 {
		t.Fatalf("addresses = %d, want 2", got)
	}
	if err := engine.SetNetwork("rmnet_data0", `["not-a-prefix"]`, ""); err == nil {
		t.Fatal("SetNetwork accepted an invalid prefix")
	}
	networkKey := engine.networkKey
	if err := engine.SetNetwork(
		"rmnet_data0",
		`["192.0.2.10/24","2001:db8::1/64"]`,
		"192.0.2.1",
	); err != nil {
		t.Fatal(err)
	}
	if engine.networkKey != networkKey {
		t.Fatal("unchanged network snapshot was treated as a transition")
	}
	if err := engine.SetNetwork("tun0", `["192.0.2.20/24"]`, "192.0.2.1"); err != nil {
		t.Fatal(err)
	}
	if engine.networkKey == networkKey {
		t.Fatal("network transition was not detected")
	}
}

func TestTunnelRequiresTokenAndForwardsOnlyToItsDestination(t *testing.T) {
	var dialed string
	current, err := newTunnel("u4090.example.ts.net:22", func(
		_ context.Context,
		_ string,
		address string,
	) (net.Conn, error) {
		dialed = address
		local, remote := net.Pipe()
		go func() {
			defer remote.Close()
			_, _ = io.Copy(remote, remote)
		}()
		return local, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	go current.serve()
	t.Cleanup(current.close)

	address := current.listener.Addr().String()
	unauthorized, err := net.Dial("tcp", address)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.WriteString(unauthorized, "wrong\n")
	_ = unauthorized.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := bufio.NewReader(unauthorized).ReadString('\n'); err == nil {
		t.Fatal("unauthenticated tunnel connection remained open")
	}
	_ = unauthorized.Close()

	client, err := net.Dial("tcp", address)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_, _ = io.WriteString(client, current.token+"\n")
	reader := bufio.NewReader(client)
	if line, err := reader.ReadString('\n'); err != nil || line != "OK\n" {
		t.Fatalf("tunnel handshake = %q, %v", line, err)
	}
	if dialed != "u4090.example.ts.net:22" {
		t.Fatalf("dialed %q", dialed)
	}
	_, _ = io.WriteString(client, "ssh")
	payload := make([]byte, 3)
	if _, err := io.ReadFull(reader, payload); err != nil || string(payload) != "ssh" {
		t.Fatalf("forwarded payload = %q, %v", payload, err)
	}
}

func TestResetClosesTunnelsAndRemovesIdentityState(t *testing.T) {
	stateDir := filepath.Join(t.TempDir(), "embedded-tailnet")
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stateDir, "identity"), []byte("secret"), 0600); err != nil {
		t.Fatal(err)
	}
	current, err := newTunnel("u4090.example.ts.net:22", func(
		context.Context,
		string,
		string,
	) (net.Conn, error) {
		return nil, errors.New("unexpected dial")
	})
	if err != nil {
		t.Fatal(err)
	}
	go current.serve()
	engine := Engine{tunnels: map[string]*tunnel{current.id: current}}

	if err := engine.Reset(stateDir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(stateDir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("state directory still exists: %v", err)
	}
	if _, err := net.DialTimeout("tcp", current.listener.Addr().String(), 50*time.Millisecond); err == nil {
		t.Fatal("reset left tunnel listener open")
	}
	if err := engine.Reset(stateDir); err != nil {
		t.Fatalf("repeated reset failed: %v", err)
	}
}

func TestResetPreservesIdentityWhenStopFailsOrIsCancelled(t *testing.T) {
	for _, failure := range []error{errors.New("stop failed"), context.Canceled} {
		t.Run(failure.Error(), func(t *testing.T) {
			stateDir := filepath.Join(t.TempDir(), "embedded-tailnet")
			removed := false
			err := resetState(
				stateDir,
				func() error { return failure },
				func(string) error {
					removed = true
					return nil
				},
			)

			if !errors.Is(err, failure) {
				t.Fatalf("reset error = %v, want %v", err, failure)
			}
			if removed {
				t.Fatal("reset removed identity before the node stopped")
			}
		})
	}
}
