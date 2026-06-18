# 0003. Vendor & fork the desktop toolchain via composite-build substitution

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

Compiling Java/Kotlin/Android projects requires desktop developer tools — `javac`, the JDK compiler/`jdeps`, the Eclipse JDT compiler, `layoutlib`, AAPT, JAXP, etc. These assume a desktop JDK environment and are not published in forms that run on Android's ART runtime. To build on-device (see [ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md)), they have to be made to work there.

## Decision

**Fork and vendor** these tools into the repository as composite builds under `composite-builds/build-deps` and `composite-builds/build-deps-common`, and **substitute** them for their `com.itsaky.androidide.build:*` Maven coordinates via `dependencySubstitution` in `settings.gradle.kts`.

Substituted modules include `javac`, `jdk-compiler`, `jdk-jdeps`, `jdt`, `layoutlib-api`, `java-compiler`, `jaxp`, `javapoet`, and `google-java-format`, among others. Any module needing one of these gets the forked, Android-compatible version transparently.

## Consequences

**Positive**
- The desktop toolchain runs on-device — the thing that makes the whole product possible.
- We can patch compiler/tooling internals for Android compatibility and bug fixes.
- Consumers reference normal coordinates; the substitution is centralized and invisible to them.

**Negative / costs**
- A large vendored source subtree and a significant ongoing maintenance burden: tracking upstream changes and JDK/AGP/Gradle updates.
- The **majority of suppressed scanner findings live in these subtrees** — they are treated as vendored code (suppress-only, never "fixed" to our standards) per [SECURITY.md](../../SECURITY.md).
- Onboarding cost: contributors must understand the substitution model before touching builds.

## Alternatives considered

- **Depend on upstream artifacts** — rejected: they don't run on Android.
- **Require a companion desktop** — rejected: defeats the product's reason to exist.
- **Bundle a full JDK** — rejected: not feasible/licensable for on-device use in this form.

## Related

- [ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md) — the build process that runs this toolchain.
- [SECURITY.md](../../SECURITY.md) — vendored-code suppression policy.
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — dependency-substitution rules.
