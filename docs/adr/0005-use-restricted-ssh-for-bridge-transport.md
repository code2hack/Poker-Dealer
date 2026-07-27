---
status: superseded by ADR-0006
---

# Use restricted SSH for Bridge transport

Dealer connects to each Termux or Linux Host through OpenSSH, pins the Host's
SSH host key, and authenticates with one standard OpenSSH-compatible product
key pair reused across Hosts. Dealer does not import the user's general-purpose
login key. Each Host's authorized-key entry uses `restrict` plus `command=` to
force `poker-dealer-bridge stdio`, so the product key cannot open a shell,
allocate a PTY, forward ports, run user RC files, or select another command.
M1 provisions this through the normal manual SSH workflow: Dealer exports the
public key and fingerprint, and the user installs the restricted line with the
Host's absolute Bridge path. Poker–Dealer may show a copyable template but does
not edit `authorized_keys` or `sshd_config`.

This supersedes the custom Bridge WebSocket listener, private TLS CA, SPKI
certificate identity, HMAC pairing secret, and application authentication
handshake from ADR-0003 and ADR-0004. Reusing OpenSSH removes bespoke
security-sensitive transport code while retaining the product-specific Bridge
protocol, pane identity, safe input, deduplication, and resynchronization
semantics.

Mosh was rejected because it is an interactive terminal and latest-screen
state-synchronization protocol, not a lossless non-interactive application
channel; it may omit intermediate screen states and does not provide the
constrained request/response interface Poker–Dealer requires. The trade-off is
an operational dependency on `sshd`, an Android-compatible SSH client, pinned
host-key provisioning, and installation or rotation of the restricted Dealer
public key on every Host.
