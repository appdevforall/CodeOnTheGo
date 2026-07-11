# 0006. Use Koin for dependency injection, not Hilt/Dagger

- **Status:** Proposed
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

The app needs dependency injection to wire ViewModels, repositories, and managers across many modules. On a build this large (~80 modules), annotation-processing DI (Dagger/Hilt via `kapt`/KSP) adds noticeable compile time and couples DI to the Android Gradle plugin and the build graph. The team values fast iteration and simple, testable wiring.

## Decision

Use **Koin** for dependency injection.

- Modules `coreModule` and `pluginModule` declare dependencies via Koin's DSL (`single { }`, `viewModel { }`); `startKoin { }` runs in `IDEApplication`.
- Prefer **constructor injection** into ViewModels/classes.
- A small `ServiceLocator` (a `KoinComponent`) provides lazy access for components that must resolve a dependency *after* Koin starts but *before* their screen exists.

## Consequences

**Positive**
- No annotation processing → faster builds, no `kapt`/KSP for DI.
- Simple, readable DSL; trivial to override dependencies in tests.

**Negative / costs**
- DI errors surface at **runtime**, not compile time — a missing binding fails when resolved, so DI paths need test coverage.
- `ServiceLocator` is a deliberate escape hatch; over-use reintroduces hidden global state. Keep it limited and prefer constructor injection.

## Alternatives considered

- **Hilt / Dagger** — rejected: compile-time safety is real, but the `kapt`/KSP build cost and AGP coupling are significant at this module scale, and the team prioritized iteration speed.
- **Manual DI / by-hand wiring** — rejected: boilerplate and poor ergonomics across this many modules.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — technology stack (DI).
