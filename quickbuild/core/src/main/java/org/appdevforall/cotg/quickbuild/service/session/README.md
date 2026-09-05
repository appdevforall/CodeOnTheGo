# `service/session/` - the live Quick Build session and its lifecycle

This folder is the session itself: the shell that owns the domain `SessionReducer`, holds the one live session's wiring, and turns reducer effects into real work (provision, daemon respawn, proxy app rebuild, live reload). Everything stateful runs on a single-threaded dispatcher so the orchestrator's event ordering holds, and effects are launched rather than run inline so a dispatch never re-enters itself. Depends down on `domain/`; the pieces here reference each other freely.

| File | Purpose |
| --- | --- |
| [`QuickBuildSessionManager.kt`](QuickBuildSessionManager.kt) | Top-level shell: owns the reducer, state flows, and live session; wires daemon-death, crash, reconnect, and low-memory signals; runs each `SessionEffect`. |
| [`LiveReloadExecutorImpl.kt`](LiveReloadExecutorImpl.kt) | Runs one classified change-set through compile/dex/relink on the warm daemon, then deploys; every failure becomes a `BuildOutcome`, and a generation is burned only once the build reaches deploy. |
| [`LiveSession.kt`](LiveSession.kt) | Holds one live session's wiring (orchestrator, watcher, tracker, filter, mutable baseline); `adoptBaseline` moves it onto a rebuilt proxy app, and `SwitchableExecutor` swaps the executor without replacing the orchestrator. |
| [`LiveSessionFactory.kt`](LiveSessionFactory.kt) | Pure wiring that assembles a `LiveSession` from a successful provision; also rebuilds the executor and annotation baseline against a re-read proxy app on rebuild. |
| [`QuickBuildDaemonController.kt`](QuickBuildDaemonController.kt) | Owns the compile daemon's lifecycle: the epoch rule for detecting superseded respawns, respawn cleanup, and the low-memory teardown policy. |
| [`OrchestratorEventRouter.kt`](OrchestratorEventRouter.kt) | Translates each orchestrator event into session events plus tally/notify instructions the manager applies, and reports every event to metrics. |
| [`QuickBuildHistoryStore.kt`](QuickBuildHistoryStore.kt) | Interface for remembering whether the open project has ever tapped Quick Build, persisted across CoGo runs (analytics only; does not gate prebuild). |
