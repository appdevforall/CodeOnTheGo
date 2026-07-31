# 0010. Quick Build's live reload path is bounded; real Gradle stays authoritative outside it

- **Status:** Proposed
- **Date:** 2026-07-16
- **Deciders:** Code On The Go team

## Context

Quick Build gives edit-run-edit iteration a live reload path: an on-device watcher
triggers an incremental compile + dex + relink, and the payload deploys into a running
proxy app (installed under the project's real `applicationId`) over a bound service — no reinstall, no full Gradle
invocation. This is fast (~1 s warm at p50, measured on a mid-spec phone with a minimal
app; `results/phase1-gates-a56/` in the `CodeOnTheGo-build-benchmark` repo) precisely because it skips most of
what a real Gradle build does. That's only safe for a bounded class of edits; anything wider needs
Gradle's full correctness (dependency resolution, manifest merging, resource linking,
native builds) to avoid silently deploying a broken or stale app. Two terms used below:
the **proxy app build** is the one-per-baseline real Gradle build that generates and
installs the proxy app; re-running it to refresh the live reload path's baseline is a
**proxy app rebuild** (the full glossary, including the retired older names, is in
`quick-build/README.md`).

## Decision

**Live reload path (Quick Build daemon):** incremental Kotlin compile (Kotlin Build Tools API) +
`javac` + `aapt2` R regeneration + `d8` relink + deploy over the bound service. This
covers source edits, resource-value edits, and asset changes.

**Real Gradle stays authoritative** — any of the following routes to a proxy app rebuild
(a full Gradle build) instead of the live reload path:
- Manifest changes (new component, permission, etc.)
- Native `.so` changes (can't hot-reload native code)
- Edits touching annotation-processor input (kapt/KSP correctness needs a real build;
  fast-follow may run some processors incrementally in-daemon, but v1 always rebuilds
  the proxy app)
- Dependency / Gradle-file changes

**Correctness target — equivalent on the cases we care about, not 100%:** the live
reload path aims for behavioral equivalence with a real Gradle build on the supported edit
classes, verified by the benchmark corpus's output-equivalence oracles — not universal
equivalence (that would mean reimplementing Gradle). Anything outside the verified
classes routes across the boundary above.

**Never-stale invariant:** a failed or partial reload must never leave the app
silently running stale code without the failure being surfaced. Any edit the classifier
can't confidently route to the live reload path takes the conservative branch (a proxy
app rebuild), and a build failure on either path renders an error overlay rather than leaving the last-good
build looking current.

**Package identity (updated 2026-07-24 — supersedes the original `.quickbuild` suffix
decision):** the proxy app now installs under the project's **real `applicationId`**, the
same slot a Standard Run uses. There is no `.quickbuild` suffix and no separate mode.
Because both build types share one slot, switching from one to the other overwrites the
other, so CoGo **confirms the clobber** before installing (it reads the installed package's
`appComponentFactory` to tell which build occupies the slot). This buys real
package-bound behavior (Firebase, FCM into proxied services, Google Sign-In/OAuth against
the debug cert, verified app links) — which uniform component proxying
(`component-proxying-design.md`) makes honest — at the cost that Quick Build and Standard
Run no longer coexist side by side. The original suffix decision and its rationale are kept
below under Alternatives for the record.

**Release bar (proposed, final call tracked in ADFA-4128):** ship behind the experiments
flag when, on mid-spec devices, code + resource edits reload under 2 s p95, never-stale
is verified on every failure path, unsupported changes fall back to a correct full
build, and first-run install is hands-free except OS-mandated dialogs.

## Consequences

**Positive**
- The live reload path stays simple and fast because it never has to be correct for the
  cases Gradle already handles well.
- The never-stale invariant gives a hard backstop: when in doubt, a proxy app rebuild is
  always correct, just slower.

**Negative / costs**
- Any edit crossing the boundary (manifest, native, processor-input, deps) pays a full
  rebuild, which can surprise a user expecting instant reload.
- Sharing the real applicationId (2026-07-24) means Quick Build and Standard Run no longer
  coexist: switching build type overwrites the other's installed app, so each switch is a
  reinstall behind a confirm dialog (and possibly a Play Protect prompt).

## Alternatives considered

- **Hot-reload everything, including manifest/native/dependency changes** — rejected: none
  of these are safely hot-swappable (new components need a real install; native code can't
  be swapped in a running process; dependency changes need real resolution), and getting
  this wrong violates the never-stale invariant.
- **Run the proxy app under the real `applicationId`, warning the user about clobbering
  the installed app** (the original proposal) — rejected, for two reasons beyond the
  clobber itself. (1) The proxy app is a harness, not the real app: v1 proxies activities
  only, so everything else on the device that targets the real id — FCM pushes, alarms
  into Services, widgets, app links, notification taps — would route into an app that
  cannot run those components. The real id buys the *appearance* of feature parity while
  service-bound flows break silently; the suffix makes the boundary visible instead
  (real-id-bound features fail obviously, under the wrong package). (2) Every
  Quick Build <-> Standard Run switch becomes an uninstall/reinstall: the measured
  3-dialog install flow, with Play Protect re-prompting on every install of an unscanned
  app (`quick-build/corpus/results/phase1-gates-a56/`), and hand-back could no longer
  leave both paths usable side by side. Component proxying has since widened to every
  manifest component (`quick-build/docs/component-proxying-design.md`), which makes the
  harness genuinely stand in for the app — so per this entry's revisit clause this
  "run under the real id" approach, first adopted as a per-project opt-in (2026-07-20),
  became the **only** behavior on **2026-07-24**: the `.quickbuild` suffix was removed
  entirely and both build types install under the real id, gated by a destructive-styled
  **confirm-on-switch** dialog when a Run would clobber the other build's app. See the
  updated "Package identity" note under Decision above.

## Related

- [ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md) — real Gradle builds stay the
  authoritative build engine; Quick Build adds a narrower live reload path beside it,
  not a replacement.
- `quick-build/README.md` — glossary, module map and the module-local design decisions
  (watch trigger, proxy app format, transport, daemon, generations, ...).
- Jira ADFA-4128 — the ticket that introduced Quick Build; design history and the
  release bar for lifting the experiments gate live there.
