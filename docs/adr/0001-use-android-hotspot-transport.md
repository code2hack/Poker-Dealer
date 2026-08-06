---
status: accepted
last_amended: 2026-08-06
---

# Use Android hotspot transport without CXR

Poker–Dealer uses an authenticated Android TLS/TCP connection initiated by
Dealer on the Fold6 hotspot host toward a Poker listener on the tethered
RG-glasses. Real-hardware evidence showed that this direction works
bidirectionally while the Samsung hotspot blocks the reverse direction; it also
avoids the credentials, compatibility, and lifecycle dependency of
CXR-M/CXR-S/CXR-L. The throwaway evidence is preserved on
`prototype/android-hotspot-transport` at commit `9d36ed1`.

Poker binds its listener only to the active hotspot/Wi-Fi interface. Losing that
interface closes the connection epoch. A paired and enabled Poker starts the
listener foreground service after boot with one silent, content-free
notification; Android force-stop still requires the user to open Poker again.

Poker accepts enrollment only during an explicit five-minute pairing window
opened through physical Poker interaction and trusts one Dealer installation at
a time. The single-use code authenticates the public-key exchange without being
sent as plaintext. Five failed attempts close the window. Successful pairing
pins the public keys corresponding to both installations' non-exportable
Android Keystore private keys and discards the code. Later connections use
mutually authenticated TLS. A wrong
pinned identity fails closed as `Pairing mismatch`; trusting a different Dealer
requires physical replacement pairing. Physical confirmation revokes the old
Dealer immediately, and a failed replacement leaves Poker unpaired rather than
restoring stale trust. Dealer may update Poker's mutable hotspot endpoint
without pairing again only when the pinned Poker identity authenticates.
Keystore loss or invalidation returns the affected installation to unpaired
state without silently generating a new trusted identity.

Only one authenticated Dealer connection epoch may be active. A newer epoch
replaces and closes the older socket. Epoch and sequence checks reject stale,
duplicate, or out-of-order mutations. An otherwise idle connection sends a
protocol heartbeat ping every 30 seconds and closes after three unanswered
pongs; these
constants remain hardware-calibratable. Reconnect retries immediately after a
relevant Android network change or manual request, otherwise with jittered
exponential backoff from one to thirty seconds, reset after a stable connection.

After each connection, Poker restart, or detected sequence gap, Dealer sends a
fresh authoritative snapshot of every attached pile. The snapshot is identified
by revision `R`; Poker stages and validates every declared chunk, then installs
it atomically and acknowledges `R`. Live deltas newer than `R` queue until that
acknowledgment and are then released in order. A missing base, sequence gap,
bounded-queue overflow, incomplete snapshot, or staging-resource failure starts
a newer snapshot instead of dropping semantics, truncating content, or inventing
a content-size ceiling. Poker continues showing its last complete snapshot
read-only until replacement succeeds. There is no durable transport replay log.

Growing text cards use revisioned, UTF-8-offset-checked append chunks followed
by one authoritative final revision. Photo assets use identified chunks with an
exact length and SHA-256 digest on the same connection. Audio uses ordered,
session-scoped 16 kHz mono PCM16 messages. The existing 4 KiB transport frame
boundary is a chunking boundary, not an asset or recording size ceiling; no
second HTTP endpoint, listener, codec, companion channel, or cloud path is
introduced.

Poker keeps synchronized card content and active mode state in process memory.
It may persist only pairing/listener state, pairing-specific unread identifiers
and watermarks, and the last-acknowledged Dealer-owned binding map and Poker font
value together with their revisions in
private backup-excluded storage. Corrupt derived state is discarded and
resynchronized; corrupt pairing state follows the unpaired rule above.

## Consequences

- CXR and the Rokid companion data channel are not production fallbacks.
- Dealer's TCP-client role does not change its application authority.
- ADB remains installation and diagnostic control only, never a data path.
- Dealer is authoritative for retained projection, control generations,
  bindings, Poker settings, assets, and ASR recognition.
- Poker bears capture, HUD, and source-interaction work while Dealer bears
  durable storage and ASR compute.
