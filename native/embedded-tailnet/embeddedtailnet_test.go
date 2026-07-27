package embeddedtailnet

import (
	"testing"

	"tailscale.com/ipn/ipnstate"
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
	got, err := encodeStatus(status)
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"connected","nodeName":"dealer-fold6"}`; got != want {
		t.Fatalf("status = %s, want %s", got, want)
	}

	status.Health = []string{"relay-only"}
	got, err = encodeStatus(status)
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"degraded","nodeName":"dealer-fold6","health":["relay-only"]}`; got != want {
		t.Fatalf("status = %s, want %s", got, want)
	}

	status.Health = nil
	status.Self.Online = false
	got, err = encodeStatus(status)
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"state":"unavailable","nodeName":"dealer-fold6"}`; got != want {
		t.Fatalf("status = %s, want %s", got, want)
	}
}
