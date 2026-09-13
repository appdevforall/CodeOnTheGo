# 0012. Keep volatile build metadata out of module ABIs

- **Status:** Proposed
- **Date:** 2026-08-13
- **Deciders:** Code On The Go team

## Context

`:build-info` generates `BuildInfo.java` from a template and sits at the root of the
dependency graph. Five of its generated fields change from build to build:

```java
VERSION_NAME_SIMPLE     = "C-d-0810-1555"                     // wall-clock time, to the minute
VERSION_NAME_PUBLISHING = "C-d-0810-1555-98ea6f6a4-SNAPSHOT"  // time + commit hash
VERSION_NAME_DOWNLOAD   = "C-d-0810-1555-98ea6f6a4-SNAPSHOT"  // time + commit hash
CI_GIT_BRANCH           = "ci-bench"
CI_GIT_COMMIT_HASH      = "98ea6f6a4"
```

All are `public static final String`. Java and Kotlin inline compile-time constants
into every consumer, so a constant's *value* belongs to the declaring module's ABI.
Every build therefore changed `:build-info`'s ABI and forced the whole project to
recompile.

Three of the five derive from the current time (`simpleVersionName` in
`ProjectConfig.kt` formats `C-{d|r}-MMDD-HHMM`), so this fires on **any two builds a
minute apart, even of an identical commit**. That is strictly worse than the commit
hash, and it is why the problem reproduces off CI.

Measured locally with a scripted no-change scenario - no source edit whatsoever, only
a different `GITHUB_SHA`:

```
30  compileV8DebugKotlin        <- every Kotlin module in the project
12  kaptGenerateStubsV8DebugKotlin
11  kaptV8DebugKotlin
```

The same signature appears on CI (30 executed `compileV8DebugKotlin`). A build in
which nothing changed recompiles the entire tree.

The churn also propagates a second time. `common/.../BuildInfoUtils.kt` declares:

```kotlin
const val BASIC_INFO = "${BuildInfo.INTERNAL_NAME} (${BuildInfo.VERSION_NAME_SIMPLE})"
```

A Kotlin `const val` is inlined too, so `:common`'s ABI churns as well and everything
depending on `:common` recompiles from there.

## Decision

Generate the volatile fields with **non-constant initialisers**, so `javac` emits no
`ConstantValue` attribute and the values leave the ABI entirely:

```java
public static final String VERSION_NAME_SIMPLE = volatileValue("@@VERSION_NAME_SIMPLE@@");
```

The rule this encodes: **a value that changes between builds must never be a
compile-time constant.** Where it is declared matters less than whether it is
inlinable.

`:common`'s `BASIC_INFO` becomes a non-`const` `val`. This is not optional - a Kotlin
`const val` requires a compile-time constant initialiser, so it stops compiling until
corrected.

Stable fields (package name, repo coordinates, AGP versions, F-Droid flags) keep their
constant form.

Two related changes follow from the same invariant:

- `simpleVersionName` derives its timestamp from the commit being built rather than
  the wall clock, so the generated source is a function of the commit. Format and
  ordering are unchanged, so nothing product-visible moves. The calendar is fixed to
  UTC, otherwise the version would be a function of the builder's timezone too.
- `:build-info`'s Jar sets `preserveFileTimestamps = false` and
  `reproducibleFileOrder = true`. This is not optional in practice: with the timestamp
  fixed, `BuildInfo.java` became byte-identical between rebuilds while the *jar* still
  changed, because Gradle embeds per-entry timestamps by default. kapt tracks that jar
  through an input property named `internalNonAbiClasspath` - jar bytes rather than the
  ABI - so the ABI fix above cannot reach it and only a reproducible jar can.

## Consequences

**Positive**
- A commit, or the clock advancing, no longer changes any module's ABI. Recompilation
  is confined to modules whose sources actually changed: 1 Kotlin module for a no-op or
  a leaf edit, 10 for a three-module edit containing one real ABI change.
- Gradle's build cache and up-to-date checks become effective for the first time.
- The invariant is enforced by the compiler rather than by convention: reintroducing a
  `const val` over a volatile value fails the build.

**Negative / costs**
- `BuildInfo`'s volatile fields can no longer be used where Java or Kotlin requires a
  compile-time constant (annotation arguments, `when` branch constants). None of the
  current call sites need that.
- The `volatileValue()` indirection is unusual and invites "simplification" back into a
  plain constant. The generated file carries a comment saying why.
- Values move from being inlined at each call site to a single static read. The runtime
  cost is immaterial; the behaviour is unchanged.
- kapt still re-runs across its 11 modules: it resolves the full compile classpath
  rather than the ABI-normalised one. Tracked by ADFA-4598 (kapt to KSP).

## Alternatives considered

- **Move the fields into `:app`'s `BuildConfig`.** Considered first and rejected on
  evidence: `:common` and `:editor` consume `VERSION_NAME_SIMPLE`, and neither can
  depend on `:app`. It would have addressed only the two `CI_GIT_*` fields and left the
  dominant, time-based churn untouched.
- **A separate `:build-info-git` leaf module.** Same defect - it isolates the git
  fields but not the version fields that library modules genuinely need.
- **Drop the timestamp from `simpleVersionName`.** Attacks the root cause rather than
  the propagation, and would help independently. Rejected *for this ADR* because the
  version string is product-visible (Firebase release notes, tester-facing builds,
  Jira), so it is a product decision rather than a build one. Worth revisiting.
- **Leave it and rely on the remote build cache.** Does not help: the compile tasks
  miss the cache precisely because their compile classpath genuinely changed.

## Related

- [0005](0005-per-abi-product-flavors.md) - the flavor dimension that multiplies every
  build task, and so multiplies the cost of this churn.
- [Build and CI glossary](../process/build-ci-glossary.md) - *ABI change*, *ABI churn*,
  *build graph health*.
- ADFA-5126 - the ticket, with the full before/after measurements.
