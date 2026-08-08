# `:quickbuild:core` - the IDE-side half of Quick Build

Decides *what* to do on every save and drives the session that does it: watch the project,
classify each change into a [`BuildRoute`](src/main/java/org/appdevforall/cotg/quickbuild/domain/BuildRoute.kt),
run the live reload path or hand back to Gradle, and deploy the result to the running proxy app.

For what Quick Build is and how the whole loop fits together, read [`../README.md`](../README.md)
first. This file only covers what is inside this module.

## The one rule that shapes everything here

**This module is pure-JVM and Android-free by design.** No `android.*`, no `androidx.*`, no
`Context`. Every Android capability it needs is declared here as an interface - a *port* - and
implemented in `:app`, wired in one Koin module
([`QuickBuildModule.kt`](../../app/src/main/java/com/itsaky/androidide/di/QuickBuildModule.kt)).

Two things follow, and both are the point:

- The routing rules, the session state machine and the deploy policy are **unit-testable on the
  JVM** with no device and no Robolectric. That is most of `src/test/`.
- Swapping how CoGo installs an APK, watches files or reports metrics does not touch this module.

Adding a dependency on an Android type here breaks both. Add a port instead.

## Packages

Dependencies flow **down** toward `domain/`. Nothing depends upward.

| Package | Holds | Start reading at |
| --- | --- | --- |
| [`domain/`](src/main/java/org/appdevforall/cotg/quickbuild/domain/) | pure logic and value types: routing, session state, generations, deploy policy | [`ChangeClassifier`](src/main/java/org/appdevforall/cotg/quickbuild/domain/ChangeClassifier.kt), [`SessionReducer`](src/main/java/org/appdevforall/cotg/quickbuild/domain/SessionReducer.kt), [`LiveReloadOrchestrator`](src/main/java/org/appdevforall/cotg/quickbuild/domain/LiveReloadOrchestrator.kt), [`DeployPolicy`](src/main/java/org/appdevforall/cotg/quickbuild/domain/DeployPolicy.kt) |
| [`data/`](src/main/java/org/appdevforall/cotg/quickbuild/data/) | the ports themselves: file watching, device paths, the daemon process | `ProjectWatcher`, `QuickBuildPaths`, `DaemonProcessClient` |
| [`service/`](src/main/java/org/appdevforall/cotg/quickbuild/service/) | session lifecycle and the effects that touch the outside world: provision, install, deploy over AIDL | [`QuickBuildSessionManager`](src/main/java/org/appdevforall/cotg/quickbuild/service/QuickBuildSessionManager.kt), [`QuickBuildDaemonController`](src/main/java/org/appdevforall/cotg/quickbuild/service/QuickBuildDaemonController.kt), [`LiveReloadExecutorImpl`](src/main/java/org/appdevforall/cotg/quickbuild/service/LiveReloadExecutorImpl.kt) |

The split that matters: **`domain/` decides, `service/` acts.** A pure reducer computes the next
state and a list of effects; the session manager executes them. If you find yourself doing IO in
`domain/`, the logic wants to move to `service/` or the IO wants to become a port.

## Two invariants that are easy to break

- **Everything stateful runs on one dispatcher, and it must be single-threaded.** Effects are
  `launch`ed rather than run inline so a dispatch can never re-enter itself.
- **The reducer is total.** An unknown `(state, event)` pair keeps the current state and produces
  no effects, so a late or duplicate event cannot corrupt a session. Adding a state or event
  without extending the reducer silently gets you this fallback, not a compile error.

## Where the rest is

| For | Read |
| --- | --- |
| What Quick Build is, the loop, the decisions | [`../README.md`](../README.md) |
| Which file implements which pipeline step | [`../docs/pipeline.md`](../docs/pipeline.md) |
| My edit did not show up - where to look | [`../docs/debugging.md`](../docs/debugging.md) |
| The wire formats this module speaks | [`../protocol/README.md`](../protocol/README.md) |

The other halves of the feature live in sibling modules: [`../daemon/`](../daemon/) compiles,
[`../runtime/`](../runtime/) runs inside the proxy app, and
[`../../gradle-plugin/`](../../gradle-plugin/) builds it.
