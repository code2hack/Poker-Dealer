# Poker–Dealer

Poker–Dealer turns selected tmux panes into private, bidirectional wearable
conversations while preserving exact pane identity and full message text.

## Language

**Host**:
A user-scoped Termux or Linux environment containing tmux servers, OpenSSH,
and the Poker–Dealer Bridge executable.
_Avoid_: Tmux server, remote shell

**Tmux server**:
A durable socket locator through which a Host exposes one running tmux server
instance at a time. It is not a network server or authentication boundary.
_Avoid_: Host, SSH server, remote machine

**Tmux server instance**:
One lifetime of a tmux process behind a Tmux server locator. A replacement
instance cannot inherit the identity of panes from the prior instance.
_Avoid_: Tmux server, socket, session

**Dealer**:
The Fold6 companion and durable authority for conversations, cards, delivery
state, and Poker synchronization.
_Avoid_: Transport server, phone server

**Poker**:
The RG-glasses HUD and input endpoint. Poker owns only its viewport,
composition state, and explicitly persisted pending input.
_Avoid_: Product server, authoritative server

**Bridge transport**:
The supervised compound application link between Dealer and one Host: a
restricted SSH bootstrap channel plus a WebRTC data plane.
_Avoid_: Poker transport, glasses link, SSH shell

**Bridge bootstrap channel**:
The restricted SSH exec channel that authenticates the peers, exchanges
WebRTC signaling, and supervises one Bridge session. It remains open for that
session but does not carry Bridge Protocol payloads after the data plane opens.
_Avoid_: Bridge data plane, shell session, media channel

**Bridge data plane**:
The WebRTC peer connection whose reliable ordered data channel carries the
Bridge Protocol. Live media tracks are a post-MVP possibility, not an M1 media
deliverable.
_Avoid_: Bridge bootstrap channel, Poker transport

**Host identity**:
The durable SSH host-key identity that Dealer pins for one Host, independent of
the Host's network endpoint and current Dealer authorization.
_Avoid_: Endpoint, Dealer key, Bridge certificate

**Host authorization**:
The revocable, forced-command grant allowing Dealer's product SSH key to invoke
the Poker–Dealer Bridge bootstrap on one Host without shell access.
_Avoid_: Shell login, account, unrestricted authorization

**Dealer SSH key**:
The single standard OpenSSH-compatible product key pair held by Dealer and
reused across authorized Hosts. It is not the user's general-purpose login key.
_Avoid_: Per-Host key, personal SSH key, custom pairing secret

**Poker transport**:
The authenticated bidirectional application link between Dealer and Poker.
Dealer initiates the network connection and Poker listens; those socket roles
do not change product authority.
_Avoid_: Rokid transport, CXR channel, ADB tunnel
