# Android-only hotspot transport — evidence log

This file records observations from the throwaway prototype. ADB is used for
installation, lifecycle control, and reading aggregate state only. The
application transport does not use ADB forwarding or reverse tunnels.

## Environment

- Date: 2026-07-25
- Fold6 hotspot address: `10.84.179.136`
- RG-glasses hotspot address: `10.84.179.154`
- RG-glasses: Android 12, API 32, `arm64-v8a`
- Prototype application port: TCP `39817`
- `adb forward --list`: empty
- `adb reverse --list`: empty

## Topology discovery

The original topology was Fold6 TCP server and glasses TCP client.

1. The Fold6 dealer app reported `LISTENING` on `0.0.0.0:39817`.
2. From Termux on the Fold6, TCP connections to `127.0.0.1:39817`
   succeeded. This proves the app created a live listener.
3. A second Termux listener was opened on Fold6 TCP `39818`. A connection
   from the glasses to `10.84.179.136:39818` timed out.
4. A temporary listener was then opened on the glasses on TCP `39819`.
   Termux connected from the Fold6 to `10.84.179.154:39819`; the connection
   succeeded and the glasses received the exact payload `fold6-probe`.

Conclusion: this Fold6/Samsung hotspot configuration blocks tethered-client
TCP connections to the hotspot host, while hotspot-host connections to a
tethered client work. The viable application topology is therefore:

```text
Fold6 TCP client -> RG-glasses TCP server
```

TCP remains bidirectional after the Fold6 initiates it. The production design
must not assume that a hotspot client can open a socket to the phone's gateway.

## Reversed application transport

The revised applications were installed with the glasses listening on
`0.0.0.0:39817` and the Fold6 initiating the connection.

### Hello/ACK without ADB

- Both endpoints reached `CONNECTED` with one completed hello/ack.
- The glasses observed the actual peer as `10.84.179.136`.
- ADB was disconnected from `10.84.179.154:42721`; `adb devices` was empty.
- Six seconds later, the unchanged application connection was still
  `CONNECTED`. Probe counters advanced from 24 to 55 acknowledged, with zero
  outstanding probes, gaps, duplicates, out-of-order frames, or invalid
  frames.

Result: pass. The application transport remained live after ADB was removed.

### Bidirectional load

ADB remained disconnected for both runs.

- Fold6 to glasses: a requested 5,000-probe burst drained in approximately
  8 seconds. Including concurrent one-second probes, Fold6 reported 5,080 sent
  and 5,080 acknowledged, with zero outstanding or invalid frames and zero
  gaps, duplicates, or out-of-order frames.
- Glasses to Fold6: a requested 5,000-probe burst reached the Fold6 in
  approximately 16 seconds. Fold6 remained connected with zero sequence or
  frame errors. After ADB was reconnected for observation, the glasses showed
  its burst and continuous probes acknowledged with zero gaps, duplicates,
  out-of-order frames, or invalid frames.

These are application delivery results over TCP. TCP retransmission hides
radio/IP packet loss, so the prototype reports ACK expiry and sequence/frame
errors rather than claiming raw packet-loss measurements.

### Deliberate disconnect and reconnect

With ADB disconnected, the Fold6 test controller closed the application
socket. The client created a new socket and completed a fresh hello/ack in
approximately 392 ms. Connection and handshake counters advanced, with zero
outstanding probes or new sequence/frame errors.

Result: pass.

### Glasses process restart

The glasses application was force-stopped and relaunched. Its process-session
identifier changed, and the Fold6 completed a hello/ack with the new process in
approximately 3.9 seconds.

Two `lost_or_gapped` events accumulated around the process-death window. The
prototype deliberately has no persistent outbox or retransmission across
process death, so reconnect works but guaranteed delivery across application
restart does not. Production transport needs persisted state, replay, and
idempotent message handling if that guarantee is required.

### Glasses sleep/wake

Android reported the glasses as `Asleep`, not externally powered, and with no
prototype wake lock. ADB was disconnected for more than 60 seconds. During
that interval:

- the same application socket and handshake remained active;
- continuous probe acknowledgements advanced;
- reconnect count did not change;
- no additional `lost_or_gapped`, duplicate, out-of-order, or invalid-frame
  events appeared.

After wake, Android reported `Awake`; both sides remained on the same
application session and continued exchanging probes.

Result: pass for the observed interval without a wake lock.

### Fold6 screen off

The user locked the Fold6 while its hotspot remained enabled. Both prototype
wake locks were off, and ADB was disconnected. The application connection was
observed through the Fold6 app's loopback-only test controller for 303 seconds.

- Fold6 probes sent advanced from 5,446 to 5,749: +303.
- Fold6 probes acknowledged advanced from 5,445 to 5,748: +303.
- The application connection and handshake counters did not change.
- `lost_or_gapped` remained at the two events already recorded around process
  restart.
- Duplicate, out-of-order, and invalid-frame counters remained zero.
- The historical maximum receive gap did not increase.

Result: pass for a five-minute locked-screen soak with no prototype wake lock.
This demonstrates the current hotspot remained available under active traffic;
a substantially longer unattended soak is still required before treating
Samsung idle/power policy as a production guarantee.
