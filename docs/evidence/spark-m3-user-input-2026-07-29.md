# Spark M3 structured user-input evidence - 2026-07-29

## Scope

This records the live compatibility qualification for GitHub issue #26 and the experimental
`item/tool/requestUserInput` request family on DGX Spark. It qualifies only Spark app-server
`0.146.0`. It does not enable broad experimental APIs or qualify Fold6 Termux, u4090, another
version, request reissue after reconnect, or a secret value.

## Host and version boundary

- Host: DGX Spark, Ubuntu Linux ARM64 (`aarch64`).
- CLI and one-shot app-server: `0.146.0`.
- Initialized user agent:
  `poker-dealer-live-qualification/0.146.0 (Ubuntu 24.4.0; aarch64)`.
- The running long-lived daemon still reported app-server `0.145.0` during this check even though
  its managed Codex path reported `0.146.0`.

Dealer therefore gates this request on both host `spark` and exact daemon-reported app-server
version `0.146.0`. The currently running `0.145.0` daemon remains unqualified and cannot expose
the request to Dealer. It was not restarted because it may own active local client sessions.

## Live check

A temporary raw JSONL client started an ephemeral one-shot app-server thread, initialized with
experimental request transport enabled, and started a Plan-mode turn. Default mode was also
checked as a negative control and rejected the tool locally as unavailable.

The Plan-mode turn emitted one real server request with:

- numeric request ID `0`;
- method `item/tool/requestUserInput`;
- matching thread, turn, and item IDs;
- `autoResolutionMs: 60000`;
- one question in wire order;
- options `Spark (Recommended)` and `Fold6`;
- `isOther: true`; and
- `isSecret: false`.

The client returned:

```json
{"answers":{"host":{"answers":["SPARK"]}}}
```

`SPARK` exercised the live Other path rather than inventing or selecting an offered label. The
app-server then emitted authoritative `serverRequest/resolved` for request ID `0` and completed
the turn with `QUALIFIED SPARK`.

The normalized request is committed as
`user-input-0.146.0-live-request.json`. Separate compatibility fixtures cover multi-question,
null-option free text, secret metadata, null timeout, duplicate IDs, and unrenderable shapes
without retaining any entered secret.
