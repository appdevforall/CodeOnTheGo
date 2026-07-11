# 0003. Vendor & fork build/runtime dependencies via composite-build substitution

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

Code On The Go ships several dependencies that don't exist in a usable, Android-compatible published form. The most important are **desktop compiler/analysis tools** — `javac`, the JDK compiler internals (`jdk-compiler`, `jdk-jdeps`), the Eclipse JDT compiler (`jdt`), `layoutlib-api`, `jaxp`, `javapoet`, `google-java-format`, plus desugaring support and a few forked UI libraries. These assume a desktop JDK and must be patched to load and run on Android's ART runtime.

Crucially, the IDE uses these tools **at runtime, inside the app process**, to provide editor features without shelling out. For example, `javac` and `jdk-compiler` back the **Java Language Server** — parsing and analyzing the user's Java sources in-process — so we don't have to invoke a JDK `javac` via `ProcessBuilder` or hand-roll our own Java parser/analyzer.

> **Do not confuse this with the Gradle Tooling API.** Running the user's *Gradle build* is a separate mechanism ([ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md)): a **full out-of-process JDK/JVM** — the `java` binary from our terminal bootstrap packages (built in `appdevforall/terminal-packages`) — launched as a daemon via `ProcessBuilder` (see `ToolingServerRunner`), which the app drives over **JSON-RPC** (lsp4j; interfaces/models in `subprojects/tooling-api`, `tooling-api-model`, `tooling-api-events`). The vendored composite-build toolchains here are **not** used to run the Tooling API or Gradle builds.

## Decision

**Fork and vendor** these dependencies into the repo as **composite builds** under `composite-builds/build-deps` and `composite-builds/build-deps-common`, and **substitute** them for their `com.itsaky.androidide.build:*` Maven coordinates via `dependencySubstitution` in `settings.gradle.kts`. Any consuming module gets the forked, Android-compatible version transparently.

Substituted modules:
- **`build-deps`:** `javac`, `jdk-compiler`, `jdk-jdeps`, `jdt`, `java-compiler`, `layoutlib-api`, `jaxp`, `javapoet`, `google-java-format`, plus forked UI libs (`appintro`, `fuzzysearch`, `treeview`).
- **`build-deps-common`:** `constants`, `desugaring-core`.

**Why composite builds specifically:** a [composite build](https://docs.gradle.org/current/userguide/composite_builds.html) is a *separate* Gradle build wired in via `includeBuild`. Its outputs are cached and **not rebuilt unless its own sources change** — even a clean build of the main project doesn't rebuild them. That keeps these large forked subtrees out of the main build's hot path while still letting us patch them like first-party source.

## Consequences

**Positive**
- The forked compiler/analysis tools run **on-device, in-process**, powering IDE features (e.g. Java LSP) without spawning external processes or bundling a second parser.
- We can patch compiler/tooling internals for ART compatibility and bug fixes.
- Consumers reference normal coordinates; substitution is centralized in `settings.gradle.kts` and invisible to them.
- Composite-build isolation keeps these subtrees from inflating incremental build times.

**Negative / costs**
- A large vendored source subtree and ongoing maintenance: tracking upstream and JDK/AGP/Gradle updates.
- The **majority of suppressed scanner findings live in these subtrees** — treated as vendored code (suppress-only, never "fixed" to our standards) per [SECURITY.md](../../SECURITY.md).
- Onboarding cost: contributors must understand both the substitution model **and** the runtime-toolchain vs. Tooling-API distinction before touching builds.

## Alternatives considered

- **Depend on upstream artifacts** — rejected: they don't load/run on Android's ART.
- **Shell out to an external `javac`/analyzer for editor features** — rejected: slow, heavyweight per keystroke, and requires a full JDK on the runtime path just for analysis (that JDK exists, but for running Gradle builds, not for in-editor LSP).
- **Write our own Java parser/analyzer** — rejected: enormous effort to match `javac`/JDT fidelity.
- **Regular (non-composite) subprojects** — rejected: they'd rebuild with the main build and slow every incremental compile.

## Related

- [ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md) — the **separate** out-of-process JDK + Tooling API that runs Gradle builds (contrast, not the consumer of this toolchain).
- [SECURITY.md](../../SECURITY.md) — vendored-code suppression policy.
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — dependency-substitution rules.
