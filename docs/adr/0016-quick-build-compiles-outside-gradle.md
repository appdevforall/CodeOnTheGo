# 0016. Quick Build's live reload path compiles incrementally outside Gradle

- **Status:** Proposed
- **Date:** 2026-08-12
- **Deciders:** Code On The Go team

## Context

[ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md) builds on device through real Gradle so results match a desktop build, and rejects a custom build engine. That still holds for anything a user installs or ships.

Quick Build (ADFA-4128) does a different job: fast live reload, so a developer can iterate while writing code. A standard incremental Gradle build of a single app-module edit medians 4.7 s on a Galaxy A56 and 18.4 s on an A06, against 1.1 s and 2.8 s for Quick Build `[measured on a56, a06]`.

Most of that time is not spent on the edit. A one-line Kotlin edit takes 7.8 s to build incrementally on an A06:

- launch and configuration, 3.9 s - paid whatever the edit touched
- packaging and install, 1.1 s - to make an APK a running app does not need
- dex and resource link, 1.4 s - on outputs the edit did not change
- kotlinc, 1.2 s - the only stage the edit created

The first three cannot be sped up or skipped.

## Decision

**Quick Build's live reload path does not use Gradle.** `:quickbuild:daemon`, a JVM child process of CoGo, compiles Kotlin with the Kotlin Build Tools API and Java with javac, then dexes with d8 and relinks resources with aapt2, using the SDK already on the device. No AGP, no r8.

**Gradle handles what live reload cannot.** It still provisions the proxy app through the existing Tooling API path, and still builds every edit the classifier declines. Nothing a user installs or ships comes out of the daemon.

**One compiler, not two.** Quick Build needs Kotlin 2.3.x for faster, more robust incremental compilation. Until the rest of CoGo moves up, the APK carries two Kotlin compilers. The move is in review as ADFA-2602; unifying them is ADFA-4931.

## Consequences

**Positive**

- The edit loop is about 5x faster, and the gain is bigger on slower devices.
- The compiler stays warm between edits - the biggest single latency lever, and something Gradle cannot do.
- A compiler crash kills the daemon, not the IDE, and the daemon can be shut down to give Gradle its memory back.

**Negative - inherent to the decision**

- Output is not identical to Gradle's. That is deliberate: close enough on the cases that matter beats full compatibility.
- A second build pipeline to maintain. It will drift from AGP, and we cannot use Gradle as ground truth, so it needs its own ongoing testing - which is slow, because builds on low-spec devices are slow.

**Negative - solvable with more work**

- No annotation processing. kapt and KSP edits go to Gradle; KSP looks tractable, see [ksp-kapt-feasibility.md](../../quickbuild/docs/ksp-kapt-feasibility.md).
- Live reload covers a narrow set of edits today; the rest fall back to Gradle. Conservative defaults, not hard limits.
- Memory is not tuned. Gradle and Quick Build share it, and idle timeouts are all that keeps them out of each other's way.

## Alternatives considered

- **Gradle with fewer tasks** — rejected: the cost is mostly configuration and task-graph work, which fewer tasks do not remove, and it still builds an APK rather than a deployable payload.
- **Compile in-process inside the IDE** — rejected for ADR 0002's own reason: a compiler OOM would take the editor with it.
- **Replace the proxy-app build too** — rejected: it would drift from AGP on the one artifact where that is unacceptable.
- **ART hot-swap (Apply Changes)** — rejected: needs an attached debugger and replaces only method bodies.
- **Patch the android.jar** — rejected as infeasible; see [why not android.jar](../../quickbuild/docs/why-not-android-jar.md).

## Related

- [ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md) — still governs full builds and Quick Build's provisioning.
- [ADR 0004](0004-embedded-termux-runtime.md) — the daemon runs on the bundled JDK.
- [`quickbuild/README.md`](../../quickbuild/README.md) — design and measured numbers.
