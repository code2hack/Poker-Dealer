//go:build android

package embeddedtailnet

import "tailscale.com/net/netmon"

func updateNetworkRoute(interfaceName, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(interfaceName)
	netmon.UpdateLastKnownDefaultGateway(gateway)
}
