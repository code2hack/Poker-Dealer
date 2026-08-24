# CXR Mobile-Path Architecture Correction

**Status:** Accepted architecture correction  
**Date:** 2026-08-24  
**Repository:** `code2hack/Poker-Dealer`  
**Scope:** Dealer ↔ Poker transport direction after Legacy extraction

---

## 1. Decision

The successor MUST NOT treat CXR-M as the already-selected Dealer-side production transport.

The current transport direction is:

1. **CXR-L `CUSTOMAPP` is the first mobile-side candidate to qualify for Dealer ↔ Poker.**
2. **CXR-M + CXR-S remains the fallback/alternative candidate** if CXR-L fails the required data-plane, lifecycle, recovery, security, background, or performance gates.
3. The exact production CXR path remains unqualified until real-device evidence exists.

This decision supersedes any earlier wording in `HANDOFF.md` or `SPEC/migrations/legacy-extraction.md` that says or implies that CXR-M is already the canonical successor transport or that CXR-L is only a deployment/install concern.

---

## 2. Why this changed

Current CXR-L v1.0.1 documentation exposes a `CUSTOMAPP` session model for a specific Rokid-side package and bidirectional custom-command traffic, in addition to app lifecycle/deployment capabilities.

That makes CXR-L relevant to the actual Poker-Dealer application data plane, not merely APK deployment.

CXR-L may also simplify Dealer onboarding by combining user-mediated Rokid authorization, custom-app targeting, app installation/update/start control, and application messaging through the Rokid AI app.

These advantages are hypotheses until qualified on the real target devices and software versions.

---

## 3. Candidate topology

Preferred first qualification target:

```text
Codex app-server
       │
       ▼
Dealer
Android client
       │
 CXR-L CUSTOMAPP
       │
 Rokid AI app / Rokid link stack
       │
       ▼
Poker
Rokid client
```

The exact Rokid-side SDK/bridge implementation used by Poker remains a qualification detail. Do not force a CXR-S abstraction into Dealer core merely because earlier designs assumed CXR-M ↔ CXR-S.

Fallback/alternative qualification target:

```text
Dealer
Android client
       │
     CXR-M
       ⇅
     CXR-S
       │
       ▼
Poker
Rokid client
```

---

## 4. Effect on Legacy extraction

**The Legacy extraction scope does not change.**

The extraction worker must still:

- preserve the validated Dealer ↔ Codex app-server core;
- remove the Legacy NSD/TCP/PAKE/pinned-mTLS Poker transport from the active successor production path;
- carve Dealer orchestration away from old Poker transport concerns;
- create a project-owned, transport-neutral Poker-facing seam;
- prove Dealer ↔ Codex works with Poker disconnected;
- defer real CXR implementation until after extraction.

The extraction MUST NOT implement CXR-L or CXR-M as part of the extraction milestone unless a minimal compile-time stub is required by the transport seam.

The seam must remain neutral enough that a later concrete implementation can be selected from evidence, for example conceptually:

```text
Dealer core
    │
Poker semantic protocol
    │
PokerTransport
    │
    ├── CxrLPokerTransport      first candidate
    └── CxrMPokerTransport      fallback/alternative
```

The exact type names are non-normative.

---

## 5. Extraction rules superseded by this decision

While executing `SPEC/migrations/legacy-extraction.md`, interpret all CXR-specific language as follows:

- “CXR-M ↔ CXR-S redesign” → **Rokid CXR redesign; CXR-L `CUSTOMAPP` qualifies first**.
- “Only then begin CXR-M integration” → **Only then begin CXR qualification/integration; CXR-L is first candidate**.
- “Poker is a Rokid client using CXR-S” → **Poker is a Rokid client using the selected qualified Rokid-side CXR integration**.
- “CXR-M/S transport contract” → **selected CXR transport contract**.
- “future `CxrPokerTransport`” → **transport-neutral seam with CXR-L-first qualification**.

No retained Dealer ↔ Codex module may import concrete CXR-L or CXR-M SDK types.

---

## 6. Required CXR-L qualification before selection

CXR-L is not canonical merely because `CUSTOMAPP` exists.

A dedicated later qualification must prove at minimum:

- normal third-party Android client usability;
- normal third-party Rokid client usability;
- deterministic user authorization;
- deterministic target-package/session establishment;
- Dealer → Poker arbitrary semantic messages;
- Poker → Dealer unsolicited/asynchronous semantic messages;
- ordering behavior;
- duplicate/loss behavior;
- payload-size limits;
- binary payload behavior;
- throughput for retained history and images;
- latency for interactive controls and streaming output;
- sustained model-output streaming suitability;
- backpressure behavior;
- Rokid AI app lifecycle dependency;
- Dealer process death/recreation;
- Poker process death/recreation;
- Rokid AI app process death/recreation;
- device reboot recovery;
- Bluetooth/link loss and recovery where relevant;
- screen-off/background behavior;
- offline behavior;
- authorization/token expiry and renewal;
- app/channel isolation and security properties;
- failure semantics sufficient for accepted/rejected/unknown mutation handling and no blind replay;
- battery and thermal behavior.

A successful CXR connection MUST NOT by itself imply that Poker is render-ready or mutation-ready. The final protocol still requires an explicit readiness/synchronization state above transport.

---

## 7. Security rule

Do not assume that CXR-L authorization automatically satisfies every Poker application-authentication requirement.

Qualification must determine what CXR-L actually proves about:

- paired physical devices;
- Dealer application identity;
- Poker application identity;
- channel isolation;
- confidentiality/integrity;
- replay resistance;
- authorization lifetime.

If CXR-L gives sufficient guarantees, custom PAKE/mTLS may stay deleted.

If it proves device/link trust but not sufficient Dealer/Poker application isolation, add only the minimum application-layer authenticated envelope required above CXR.

If its security/lifecycle semantics are insufficient, reject CXR-L as the production transport candidate rather than weakening the distributed-state safety model.

---

## 8. Qualification order

After Legacy extraction closes:

```text
CXR-L CUSTOMAPP isolated probe
        │
        ├── passes required gates strongly
        │        ↓
        │   CXR-L becomes selected path
        │
        └── fails / materially weaker
                 ↓
          qualify CXR-M + CXR-S
                 │
                 ├── passes → select CXR-M/S
                 └── fails  → reopen transport decision
```

Do not maintain two permanent production transports merely for theoretical flexibility.

---

## 9. Terminology

Continue using the successor vocabulary:

- **Dealer** — Android client
- **Poker** — Rokid client
- **Codex app-server** — backend
- **Codex host** — execution host when host behavior matters
- **CXR-L** — first Dealer-side Rokid transport candidate
- **CXR-M/CXR-S** — fallback/alternative Rokid transport candidate

Specific personal hardware names remain evidence/qualification details, not product-domain concepts.

---

## 10. Worker instruction

Any implementation worker currently executing Legacy extraction should **continue the extraction rather than restarting it**.

The only immediate implementation consequence is:

> **Keep the Poker-facing seam transport-neutral and do not bake CXR-M assumptions into it.**

Do not expand the extraction milestone into CXR-L research or implementation. Finish the validated Dealer ↔ Codex extraction first.
