# 0007. Enforce StrictMode via a custom whitelist engine

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

StrictMode catches main-thread disk/network I/O and leaked resources early. But Code On The Go is a large app built on inherited and vendored code (the AndroidIDE lineage, the forked toolchain in [ADR 0003](0003-vendored-forked-desktop-toolchain.md), Termux) that trips many StrictMode violations we can't reasonably fix.

Both blunt options fail us: disabling StrictMode hides regressions in **our** code, while a death/penalty policy crashes or spams on **third-party** violations we don't control.

## Decision

Build a **custom StrictMode whitelist engine** that suppresses specific, known violations while still surfacing everything else.

Components (in `app/.../app/strictmode/`): `StrictModeManager`, `WhitelistBuilder`, `WhitelistEngine`, `ViolationDispatcher`, `ViolationListener`, `ViolationHandler`, `StrictModeConfig`, with tests (`WhitelistEngineTest`, `WhitelistRulesTest`).

**Policy:** the whitelist is **only** for vendor/framework violations we can't change. App-owned violations must be **fixed**, not whitelisted. (This is the same suppress-only-what-we-don't-own philosophy applied to security scanners in [SECURITY.md](../../SECURITY.md).)

## Consequences

**Positive**
- StrictMode stays useful in a large inherited codebase — our regressions still surface.
- Suppressions are explicit, rule-based, reviewable, and unit-tested.

**Negative / costs**
- Custom infrastructure to maintain and understand.
- Risk of over-whitelisting if the policy isn't enforced — so an app-owned entry added to the whitelist is a review red flag, not a fix.

## Alternatives considered

- **StrictMode off** — rejected: loses regression detection on our own code.
- **StrictMode with `penaltyDeath`/`penaltyLog` globally** — rejected: crashes/spams on vendored violations we can't fix.
- **Per-call `allowThreadDiskReads()` suppressions** — rejected: scatters suppressions through the code, easy to abuse, and impossible to audit centrally.

## Related

- [REVIEW.md](../../REVIEW.md) — §3 threading & StrictMode review guidance.
- [SECURITY.md](../../SECURITY.md) — the parallel suppress-only-vendor policy for scanners.
