# 0006. Use Koin for dependency injection, not Hilt/Dagger

- **Status:** Proposed
- **Date:** 2026-06-18
- **Deciders:** Code On The Go team

## Context

The app needs dependency injection to wire ViewModels, repositories, and managers across many modules. Two things shape the choice. First, the build is large (~80 modules) and **many modules are pure JVM/Kotlin, not Android** (tooling, LSP, builder-model, shared infra); Hilt is built around Android components (`Application`/`Activity`/`ViewModel`) and the Android Gradle plugin, so it fits that surface poorly. Second, annotation-processing DI (Dagger/Hilt) adds compile time across the whole graph. The team also values fast iteration and simple, testable wiring.

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

- **Hilt / Dagger.** Hilt is Google's *recommended* DI for Android, and compile-time graph validation is a real advantage; modern Hilt/Dagger also run on **KSP**, which narrows (though doesn't erase) the annotation-processing cost. Rejected anyway: Hilt is coupled to Android components and the Android Gradle plugin, a poor fit for our large non-Android (pure JVM/Kotlin) module surface, and any processing cost still applies across ~80 modules. We accept runtime-resolved DI (covered by tests) in exchange for zero processing and one DI model usable in Android and non-Android modules alike.
- **Manual DI / by-hand wiring** — rejected: boilerplate and poor ergonomics across this many modules.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — technology stack (DI).
