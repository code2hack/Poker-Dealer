# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- `AGENTS.md`
- `CONTEXT.md`
- `SPEC.md`
- accepted ADRs under `docs/adr/`

This is a single-context repo. If a future `CONTEXT-MAP.md` appears, follow it for topic-specific context files.

## Use the glossary's vocabulary

When output names a domain concept, use the term as defined in `CONTEXT.md`. Do not drift to abandoned tmux-pane, screen-scraping, bridge, WebRTC-host, terminal-emulator, CXR, or direct-Termux-socket terms as current design.

## Flag ADR conflicts

If output contradicts an accepted ADR, surface it explicitly rather than silently overriding it.
