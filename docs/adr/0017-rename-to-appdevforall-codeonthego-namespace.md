# 0017. Rename the application id / namespace to `org.appdevforall.codeonthego`

- **Status:** Proposed
- **Date:** 2026-10-06
- **Deciders:** Code On The Go team
- **Supersedes:** [ADR 0008](0008-retain-androidide-namespace.md)

## Context

[ADR 0008](0008-retain-androidide-namespace.md) kept the inherited `com.itsaky.androidide`
application id / Gradle namespace after the AndroidIDE -> Code On The Go rebrand, applying the new
identity only at the presentation layer. That decision's reasoning stands on its own: an
application id change breaks the update path for existing installs, and the terminal bootstrap
packages (built separately in `appdevforall/terminal-packages`) have the package name baked in, so
the app and those packages must flip atomically across both repos.

The team has since decided the namespace mismatch (tracked as
[ADFA-174](https://appdevforall.atlassian.net/browse/ADFA-174)) is worth resolving despite that
cost. This ADR records that reversal and the sequencing it requires, rather than silently
reopening or editing ADR 0008.

Scope, per a `git grep` audit of the tree at decision time: ~2,606 files / ~11,088 occurrences of
`com.itsaky.androidide` across 67 modules, ~697 directories under a `com/itsaky/androidide` path,
one source-of-truth build constant (`BuildConfig.PACKAGE_NAME`), several hardcoded manifest
strings (permissions, actions, provider authorities, a cross-app `logsender` package reference),
and external wiring keyed to the current identity (Firebase app registrations, the
`well-known-worker`'s `assetlinks.json` for Android App Links, two CI workflows, Play Store/F-Droid
listings).

## Decision

**Rename** the application id / Gradle namespace and the Kotlin/Java package declarations from
`com.itsaky.androidide` to `org.appdevforall.codeonthego`, sequenced as:

1. Cross-repo unblock with `appdevforall/terminal-packages` — must complete first; the terminal
   bootstrap packages and this app's application id change together, atomically, not
   incrementally.
2. Mechanical rename of source packages, directories, imports, and vendored build-logic
   coordinates ([ADR 0003](0003-vendored-forked-desktop-toolchain.md)) — reversible, done as a
   stacked PR series, landed independently of the identity flip below.
3. The application id / namespace flip itself — irreversible for existing installs, sequenced
   last and only once step 1 is confirmed ready.
4. External re-wiring (Firebase, `assetlinks.json`, CI, store listings) and an explicit decision on
   the migration story for users on the old application id.

## Consequences

**Positive**
- Codebase namespace matches the product name and the rebrand is complete, not just
  presentation-layer.
- Removes the standing confusion ADR 0008 itself flagged as a known cost.

**Negative / costs**
- Repeats, in reverse, the disruption ADR 0008 avoided: existing installs under
  `com.itsaky.androidide` do not receive this as an update; a migration story (ADFA-174) must be
  decided before the identity flip ships.
- Requires coordinated, atomic work in a second repository (`appdevforall/terminal-packages`)
  outside this team's sole control.
- Large mechanical diff (~2,600 files) carries real risk of missed references; needs a thorough
  sweep and build/test verification per module rather than a single trust-the-script pass.

## Alternatives considered

- **Keep ADR 0008's decision** — rejected: the team decided the namespace/product-name mismatch is
  worth resolving now.
- **Partial rename (code packages only, keep `applicationId`)** — rejected for this decision: the
  goal is to resolve the mismatch end-to-end, including the published identity, not just the
  source tree.

## Related

- [ADR 0008](0008-retain-androidide-namespace.md) — the decision this supersedes.
- [ADR 0003](0003-vendored-forked-desktop-toolchain.md) — vendored coordinates affected by the
  rename.
- [ADFA-174](https://appdevforall.atlassian.net/browse/ADFA-174) — tracking ticket.

## Implementation notes

- **Still `com.itsaky.androidide`, by design:** vendored build logic and forks under
  `composite-builds/` (`build.*`, `plugins.*` build plugins, `desugaring`, `layoutlib`, `zipfs2`,
  `javac.config`, `fuzzysearch`), the `buildinfo.BuildInfo` template, the external
  `android-tree-sitter` library, and the `com.itsaky.androidide.gradle:gradle-tooling-api` artifact.
  None of these is tied to the application id.
- **Hidden copies of the id** found by an on-device test of the new bootstrap: the data-dir
  constants in `build-deps-common/constants` (`ANDROIDIDE_HOME`, `MAVEN_LOCAL_REPOSITORY`),
  `TermuxConstants.TERMUX_PACKAGE_NAME`, the init-script template in `GenerateInitScriptTask`,
  and the app id used as the injected Gradle plugin id.
- **Firebase:** the app needs a Firebase client registered for `org.appdevforall.codeonthego`;
  `AnalyticsManager` throws at first use without one.
