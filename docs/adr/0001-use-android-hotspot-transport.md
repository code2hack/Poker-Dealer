---
status: accepted
last_amended: 2026-08-07
---

# Use Bluetooth-bond bootstrap with Android Wi-Fi transport without CXR

Poker–Dealer uses the Android Bluetooth bond between the Fold6 and RG glasses as
the sole user trust decision. A bonded pair automatically bootstraps the
application transport over a private secure RFCOMM service; synchronized product
data still uses the validated Dealer-initiated Android TLS/TCP connection over
ordinary Wi-Fi or the Fold6 hotspot.

Real-hardware evidence established the viable data-plane direction as Dealer on
the Fold6 initiating toward a Poker listener on the RG glasses. The Samsung
hotspot blocks the reverse direction while the resulting TCP connection is fully
bidirectional. The throwaway evidence is preserved on
`prototype/android-hotspot-transport` at commit `9d36ed1`. The qualified Poker
application port is TCP `39817`.

## Bluetooth bond is trust

The existing Android Bluetooth bond is the complete Poker–Dealer trust ceremony.
Poker–Dealer MUST NOT add a `Pair Dealer`, `Replace Dealer`, numeric code, QR
code, IP/port form, or second `Trust Dealer?` confirmation in the normal
production flow.

When its listener service is enabled, Poker exposes one project-private secure
RFCOMM service UUID. Dealer enumerates Android `BOND_BONDED` devices and probes
that service automatically. Dealer remembers the exact Android bonded-device
identity after the first successful bootstrap; friendly names are display-only
and MUST NOT be used as trust identity. When there is no remembered Poker peer,
exactly one bonded device answering the private service may be adopted
automatically. Multiple matching bonded Poker devices are ambiguous and fail
closed rather than silently selecting one.

If no bonded Poker exists, Dealer directs the user to Android Bluetooth settings
and Poker reports that it is waiting for a Bluetooth-paired Dealer. Android's
`BLUETOOTH_CONNECT`/Nearby Devices permission is an application capability
permission only and MUST NOT be presented as another Poker–Dealer trust
confirmation.

Temporary Bluetooth disconnection does not revoke trust and does not tear down a
healthy Wi-Fi connection. When the remembered peer transitions to `BOND_NONE`,
both sides revoke the Poker–Dealer relationship, delete the pinned peer transport
record and endpoint, and close the Wi-Fi connection. Rebonding allows automatic
bootstrap again.

## Automatic application-key bootstrap

Bluetooth is the bootstrap/discovery channel, not the product data plane. The
secure RFCOMM exchange carries only bounded control material needed to establish
or refresh the Wi-Fi transport:

- bootstrap protocol version and capabilities;
- fresh random nonces from both endpoints;
- each installation's Android-Keystore public key;
- signatures proving possession of those private keys over the complete
  bootstrap transcript; and
- Poker's current ordinary-Wi-Fi IPv4 endpoint and fixed Poker listener port.

Private keys never leave Android Keystore. The Bluetooth bond authorizes
provisioning or rotation of the app-level transport keys, so loss or invalidation
of one app's Keystore identity while the Bluetooth bond remains intact triggers
automatic key recreation and reprovisioning rather than a new user trust
ceremony. A malformed bootstrap, invalid signature, non-bonded peer, wrong
remembered bonded-device identity, or ambiguous peer set fails closed.

Each Android endpoint independently verifies that the RFCOMM peer is currently
`BOND_BONDED` and binds the exact locally observed bonded-device identity to the
resulting trust record; the Bluetooth address itself is not used as a portable
cross-device credential. After both key-possession proofs succeed, each side
atomically pins the peer app public key. Dealer also records the authenticated
Poker Wi-Fi endpoint. The
existing mutually authenticated TLS connection then starts over Wi-Fi. A later
secure bootstrap from the same bonded peer may refresh a changed Wi-Fi endpoint
or rotate an app key without manual input.

## Wi-Fi data plane

Poker binds its TLS listener only to the active ordinary hotspot/Wi-Fi interface.
Dealer initiates the connection. Losing that interface closes the connection
epoch; Bluetooth bootstrap may refresh the endpoint when Wi-Fi returns.

Only one authenticated Dealer connection epoch may be active. A newer epoch
replaces and closes the older socket. Epoch and sequence checks reject stale,
duplicate, or out-of-order mutations. An otherwise idle connection sends a
protocol heartbeat ping every 30 seconds and closes after three unanswered
pongs; these constants remain hardware-calibratable. Reconnect retries
immediately after a relevant Android network change or bootstrap endpoint
refresh, otherwise with jittered exponential backoff from one to thirty seconds,
reset after a stable connection.

After each connection, Poker restart, or detected sequence gap, Dealer sends a
fresh authoritative snapshot of every attached pile. The snapshot is identified
by revision `R`; Poker stages and validates every declared chunk, then installs
it atomically and acknowledges `R`. Live deltas newer than `R` queue until that
acknowledgment and are then released in order. A missing base, sequence gap,
bounded-queue overflow, incomplete snapshot, or staging-resource failure starts
a newer snapshot instead of dropping semantics, truncating content, or inventing
a content-size ceiling. Poker continues showing its last complete snapshot
read-only until replacement succeeds. There is no durable transport replay log.

Growing text cards use revisioned, UTF-8-offset-checked append chunks followed by
one authoritative final revision. Photo assets use identified chunks with an
exact length and SHA-256 digest on the same Wi-Fi connection. Audio uses ordered,
session-scoped 16 kHz mono PCM16 messages. The existing 4 KiB transport frame
boundary is a chunking boundary, not an asset or recording size ceiling; no
second HTTP endpoint, CXR channel, ADB tunnel, codec, companion channel, or cloud
path is introduced.

A bonded and enabled Poker starts the listener foreground service after boot
with one silent, content-free notification and makes the RFCOMM bootstrap service
available. Android force-stop is respected and requires a manual app open.

Poker keeps synchronized card content and active mode state in process memory.
It may persist only the remembered bonded Dealer identity, pinned app peer key,
listener state, pairing/trust-scoped unread identifiers and watermarks, and the
last-acknowledged Dealer-owned binding map and Poker font value together with
their revisions in private backup-excluded storage. Corrupt derived state is
discarded and resynchronized. Corrupt app peer state is cleared and may be
reprovisioned automatically only while the remembered Android Bluetooth bond is
still present.

## Consequences

- Bluetooth bonding is the only user trust ceremony; normal Poker–Dealer startup
  is automatic after the devices are bonded.
- Bluetooth carries discovery and bounded trust/bootstrap metadata only; cards,
  controls, photos, and ASR PCM remain on Wi-Fi.
- App-level mTLS keys remain useful as transport credentials without duplicating
  Android's user trust decision.
- CXR and the Rokid companion data channel are not production fallbacks.
- Dealer's TCP-client role does not change its application authority.
- ADB remains installation and diagnostic control only, never a product data
  path or bootstrap path.
- Dealer is authoritative for retained projection, control generations,
  bindings, Poker settings, assets, and ASR recognition.
- Poker bears capture, HUD, and source-interaction work while Dealer bears
  durable storage and ASR compute.
