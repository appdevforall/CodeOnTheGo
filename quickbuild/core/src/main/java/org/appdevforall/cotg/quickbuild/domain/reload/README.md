# `domain/reload/` - the live-reload decision layer

Pure-JVM types that decide what one quick build should do to the running proxy app: whether to hot-swap or restart, which generation the payload carries, and whether an install may proceed. No Android. The `LiveReloadOrchestrator` schedules builds (at most one in flight, everything else coalesced into a never-lost pending set); `DeployPolicy` decides restart vs recreate from the baseline's component facts.

| File | Purpose |
| --- | --- |
| [`LiveReloadOrchestrator.kt`](LiveReloadOrchestrator.kt) | Schedules builds single-flight, coalesces pending changes, escalates Gradle-needing routes, and emits `OrchestratorEvent`s; also defines `OrchestratorEvent`. |
| [`LiveReloadExecutor.kt`](LiveReloadExecutor.kt) | Interface that runs one build end to end; defines `BuildRequest`, `BuildOutcome`, and `BuildDiagnostic`. |
| [`DeployPolicy.kt`](DeployPolicy.kt) | Restarts every code-bearing deploy when the app declares a service, provider or custom `Application`, since the payload redefines those classes whatever the edit touched; defines `DeployDecision`. |
| [`ComponentInfo.kt`](ComponentInfo.kt) | One manifest component the proxy-app build recorded; `ComponentKind` and the `RESTART_SENSITIVE_KINDS` set. |
| [`ClassHeader.kt`](ClassHeader.kt) | Parses a class file's name/superclass/interfaces via a constant-pool walk. Currently unused - kept for the in-place-redefinition follow-up, which needs per-class facts again. |
| [`GenerationTracker.kt`](GenerationTracker.kt) | Hands out monotonically increasing generation numbers, persisted before use; defines the `GenerationStore` port. |
| [`RealIdInstall.kt`](RealIdInstall.kt) | Decides when installing under the project's real applicationId needs clobber confirmation or a signature refusal. |
