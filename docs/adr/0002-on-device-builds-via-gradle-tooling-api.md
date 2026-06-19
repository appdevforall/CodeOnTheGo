# 0002. On-device builds run real Gradle out-of-process via the Tooling API

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

The product's core promise is to build and deploy *real* Android apps on-device, offline — producing the same results as a desktop Gradle build (correct dependency resolution, AGP behavior, manifest merging, R8/D8), not an approximation.

Gradle is memory-hungry and can OOM or crash, especially on a phone. Running it inside the IDE process would couple the build's lifecycle and memory to the UI: a build OOM would take down the editor, and a stuck build couldn't be cleanly killed.

## Decision

Run builds with the **Gradle Tooling API in a separate JVM process**, and have the app drive it over IPC.

- `:subprojects:tooling-api` — the client-facing API and `ToolingApiLauncher`.
- `:subprojects:tooling-api-impl` — the forked process (`tooling.impl.Main`) that hosts the Tooling API connection and runs the build.
- `:subprojects:tooling-api-events` / `:subprojects:tooling-api-model` — the serializable event/model types exchanged across the process boundary.

The app streams progress/events back from this process and renders them (e.g. `BuildState`, build output). This depends on the vendored Gradle/toolchain from [ADR 0003](0003-vendored-forked-desktop-toolchain.md).

## Consequences

**Positive**
- Build crashes and OOMs are isolated from the IDE — the UI survives a failed build.
- Real Gradle semantics, so on-device builds match desktop builds.
- Builds can be cancelled/killed and the process restarted independently.

**Negative / costs**
- IPC complexity: every model and event crossing the boundary must be serializable and versioned (`tooling-api-events`/`-model`).
- Process startup time and additional memory for a second JVM.
- The Tooling API client/impl must stay in lockstep with the vendored Gradle version.

## Alternatives considered

- **Run Gradle in-process** — rejected: an OOM or crash would kill the IDE, and the build lifecycle would be entangled with the UI.
- **A custom/cut-down build engine** — rejected: enormous effort and a permanent correctness gap versus real Gradle/AGP.
- **Cloud/remote builds** — rejected: violates the offline-first requirement.

## Related

- [ADR 0003](0003-vendored-forked-desktop-toolchain.md) — the forked toolchain the build process runs.
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — build engine module group.
