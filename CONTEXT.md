# Poker–Dealer

Poker–Dealer turns selected tmux panes into private, bidirectional wearable
conversations while preserving exact pane identity and full message text.

## Language

**Dealer**:
The Fold6 companion and durable authority for conversations, cards, delivery
state, and Poker synchronization.
_Avoid_: Transport server, phone server

**Poker**:
The RG-glasses HUD and input endpoint. Poker owns only its viewport,
composition state, and explicitly persisted pending input.
_Avoid_: Product server, authoritative server

**Poker transport**:
The authenticated bidirectional application link between Dealer and Poker.
Dealer initiates the network connection and Poker listens; those socket roles
do not change product authority.
_Avoid_: Rokid transport, CXR channel, ADB tunnel
