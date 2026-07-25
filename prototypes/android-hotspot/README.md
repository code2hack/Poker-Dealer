# PROTOTYPE — Android-only hotspot transport

Question: can the Fold6 and RG-glasses replace Rokid CXR with ordinary Android
network APIs while preserving bidirectional delivery and reconnect across
sleep, wake, process restart, load, and Fold6 screen-off hotspot operation?

This is throwaway evidence, not production transport. The RG-glasses app
listens on TCP `0.0.0.0:39817`; the Fold6 app initiates an ordinary, unbound
TCP connection to the current glasses address `10.84.179.154:39817` and
automatically reconnects.

The direction is deliberate. On the current Samsung Fold6 hotspot, an
empirical connection attempt from a tethered client to the hotspot host was
blocked, while a connection initiated by the Fold6 to the tethered glasses
succeeded. The Fold6 socket is not bound to a `ConnectivityManager` network:
Android does not expose its hotspot interface as `TRANSPORT_WIFI`, so the
kernel routing table selects the tether interface.

ADB may install, launch, sleep/wake, and collect observations, but the
prototype never uses ADB forwarding or reverse tunnels. Disconnecting ADB
during a run is the strongest proof that it is not the data path.

Build both APKs from the repository root:

```sh
./prototypes/android-hotspot/build.sh
```

Outputs:

- Fold6:
  `prototypes/android-hotspot/dealer/build/outputs/apk/debug/dealer-debug.apk`
- RG-glasses:
  `prototypes/android-hotspot/poker/build/outputs/apk/debug/poker-debug.apk`

Both apps expose their complete in-memory state. Each direction sends a
one-second probe continuously; either side can launch a 5,000-frame burst.
Sequence gaps, duplicate/out-of-order frames, unanswered probes, reconnects,
long receive gaps, and RTT percentiles remain visible until reset or the
process is restarted. Timing uses Android's elapsed-realtime clock so deep
sleep is included, and inbound sequence tracking resets when the peer process
session changes.

## Fold6 loopback test control

The Dealer app also listens only on `127.0.0.1:39818` so Termux on the Fold6
can control a run without ADB. This is a test-only, unauthenticated line
protocol and is intentionally unreachable from the hotspot network.

```sh
printf 'SNAPSHOT\n' | nc 127.0.0.1 39818
printf 'BURST 5000\n' | nc 127.0.0.1 39818
printf 'PEER_BURST 5000\n' | nc 127.0.0.1 39818
printf 'DROP\n' | nc 127.0.0.1 39818
printf 'RESET\n' | nc 127.0.0.1 39818
printf 'WAKELOCK ON\n' | nc 127.0.0.1 39818
printf 'WAKELOCK OFF\n' | nc 127.0.0.1 39818
```

`BURST` sends Fold6-to-glasses probes. `PEER_BURST` uses a framed control
message over port `39817` to ask the glasses server to send the requested
glasses-to-Fold6 probe burst. Burst sizes from 1 through 20,000 are accepted.

The transport protocol on port `39817` is also prototype-only and
unauthenticated. It provides length framing, a versioned header, session and
sequence identifiers, CRC32 payload checks, hello/ack, probe/ack, and the
test-only peer-burst request; it is not suitable as a production security
boundary.
