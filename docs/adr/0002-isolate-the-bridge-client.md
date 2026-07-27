---
status: superseded by ADR-0006
---

# Isolate the Dealer bridge client

The production Kotlin client for `poker-dealer-bridge` lives in the pure-JVM
`shared:bridge-client` module so M1 can test the same SSH host verification,
public-key authentication, and framed stdio behavior that Dealer will use
without coupling it to Android UI, persistence, or service lifecycle. Bridge
wire types remain in
`shared:protocol`; placing the client directly in `apps:dealer` was rejected
because it would make server acceptance tests exercise either Android
infrastructure or a disposable substitute.
