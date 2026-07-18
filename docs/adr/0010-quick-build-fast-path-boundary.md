# 0010. Quick Build's fast path is bounded; real Gradle stays authoritative outside it

- **Status:** Proposed
- **Date:** 2026-07-16
- **Deciders:** Code On The Go team

## Context

Quick Build is a live-reload path for edit-run-edit iteration: an on-device watcher
triggers an incremental compile + dex + relink, and the payload deploys into a running
`<appId>.quickbuild` test app over a bound service — no reinstall, no full Gradle
invocation. This is fast (~1-1.3s warm) precisely because it skips most of what a real
Gradle build does. That's only safe for a bounded class of edits; anything wider needs
Gradle's full correctness (dependency resolution, manifest merging, resource linking,
native builds) to avoid silently deploying a broken or stale app.

## Decision

**Hot path (Quick Build daemon):** incremental Kotlin compile (Kotlin Build Tools API) +
`javac` + `aapt2` R regeneration + `d8` relink + deploy over the bound service. This
covers source edits, resource-value edits, and asset changes.

**Real Gradle stays authoritative** — any of the following routes to a full setup-build
rebaseline instead of the hot path:
- Manifest changes (new component, permission, etc.)
- Native `.so` changes (can't hot-reload native code)
- Edits touching annotation-processor input (kapt/KSP correctness needs a real build;
  fast-follow may run some processors incrementally in-daemon, but v1 always rebaselines)
- Dependency / Gradle-file changes

**Correctness target — equivalent on the cases we care about, not 100%:** the fast
path aims for behavioral equivalence with a real Gradle build on the supported edit
classes, verified by the benchmark corpus's output-equivalence oracles — not universal
equivalence (that would mean reimplementing Gradle). Anything outside the verified
classes routes across the boundary above.

**Never-stale invariant:** a failed or partial quick build must never leave the app
silently running stale code without the failure being surfaced. Any edit the classifier
can't confidently route to the hot path takes the conservative branch (rebaseline), and a
build failure on either path renders an error overlay rather than leaving the last-good
build looking current.

**`<appId>.quickbuild` coexistence:** the test app runs under a suffixed package id, not
the project's real `applicationId`. This gives free coexistence with a Standard Run of the
same project (different package, isolated storage, no clobbering) and correct
`${applicationId}`-based authorities (FileProvider, etc.). It also means anything bound to
the *exact* real package id does not work under Quick Build: Firebase, Google Maps API
keys, Google Sign-In/OAuth, FCM push, verified app links, Play billing. v1 accepts this —
iterate UI/logic under Quick Build, verify service-bound features on a Standard Run.

## Consequences

**Positive**
- The hot path stays simple and fast because it never has to be correct for the cases
  Gradle already handles well.
- The never-stale invariant gives a hard backstop: when in doubt, a rebaseline is always
  correct, just slower.

**Negative / costs**
- Any edit crossing the boundary (manifest, native, processor-input, deps) pays a full
  rebuild, which can surprise a user expecting instant reload.
- The `.quickbuild` package split makes a whole class of external-service integration
  untestable under Quick Build; users must remember to fall back to Standard Run for
  those flows.

## Alternatives considered

- **Hot-reload everything, including manifest/native/dependency changes** — rejected: none
  of these are safely hot-swappable (new components need a real install; native code can't
  be swapped in a running process; dependency changes need real resolution), and getting
  this wrong violates the never-stale invariant.
- **Run the test app under the real `applicationId`** — rejected: it would collide with a
  Standard Run install of the same app and require tearing down/reinstalling on every mode
  switch, defeating the coexistence goal.

## Related

- [ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md) — real Gradle builds stay the
  authoritative build engine; Quick Build is a narrower, additive fast path, not a
  replacement.
- `quick-build/README.md` — module map and the module-local design decisions (watch
  trigger, skeleton app format, transport, daemon, generations, ...).
- Execution plan `docs/product/plans/2026-07-16_ADFA-4128_live-reload-v1/execution-plan.md`
  (item B4) — the source decision this ADR documents.
