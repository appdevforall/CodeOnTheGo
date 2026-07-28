# Quick Build reliability gaps found on device

Status: five user-facing defects surfaced by device testing on 2026-07-25..28.
**None is fixed.** Each is tracked (tasks #87-#91). This doc is the decision
input: what the user actually experiences, why, how confident we are, and what
the options cost.

Provenance: `[measured on a56]` = Samsung A56, dates as noted. Untagged prose is
code reading against the working tree at `75483b6eb`. Where a defect has **no**
device evidence, it says so — one of the five is code-substantiated only.

**None of these appear in the README's "Known limitations (v1)" list.** That
list is honest about what Quick Build deliberately does not do (Gradle 9+
projects, bidirectional Kotlin/Java modules, `final` library components, API
28/29 degraded resource swaps). These five are different: they are cases where
the feature behaves worse than its own design intends, and a user would read
them as bugs rather than boundaries.

One thing that is *not* at risk: the never-stale invariant holds in all five.
Nobody ends up running stale code. What they end up with is a fast path that
went slow, went dead, or went quiet.

---

## 1. A body-only edit can escalate to a full rebaseline and a reinstall

**Task #87.**

### What the user experiences

They change one line inside a method in a project that uses Room, KSP, kapt, or
any annotation processor. Instead of a ~2 s hot reload they get a full Gradle
rebaseline — **198 s in the captured run** `[measured on a56, 2026-07-28]` — and
at the end of it a package-installer confirmation dialog asking them to reinstall
their own test app. Nothing they did warranted any of that.

The trigger is invisible to them: the compile daemon died between builds. On the
A56 the most common way that happens is ordinary — the user switches to their
test app to look at it, the system reclaims memory, the daemon goes away.

### Mechanism

Daemon death parks the session in `Degraded` and schedules a respawn. When the
respawn lands, the orchestrator re-seeds the pending set with `Unknown`:

`quick-build/.../domain/BuildOrchestrator.kt:141-151`

```kotlin
suspend fun onDaemonReplaced() {
	withEvents { events ->
		if (inFlight == null && pending.isEmpty && !pendingForced) {
			pendingSeed = true
		} else {
			markBatchArrivalLocked()
			pending = pending + ChangedFiles.Unknown      // line 147
		}
```

`Unknown` is an absorbing element (`ChangedFiles.kt:50-55` — `plus` returns
`Unknown`), so it destroys the known file set. The classifier then escalates
unconditionally, `quick-build/.../domain/ChangeClassifier.kt:45-51`:

```kotlin
ChangedFiles.Unknown -> {
	return if (annotationImpact.active) {
		BuildRoute.FullGradleBuild(InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED)   // line 47
	} else {
		BuildRoute.CodeAndResources
	}
}
```

`annotationImpact.active` is just `profile.hasProcessors` (`AnnotationImpact.kt:88`) —
"does this project configure any processor at all", not "did this edit touch
processor input". So every AP project takes the escalation.

**The pessimism is unnecessary, and that is what makes this a defect rather than
a design choice.** Three things are intact at line 147:

- **The known changed-file set still exists.** `BuildOrchestrator.onBuildFinished`
  restores the failed build's batch (`BuildOrchestrator.kt:348`,
  `pending = flight.batch + pending`) *before* the `BuildFailed` event is
  dispatched — events are collected inside the mutex and delivered after
  (`withEvents`, `:229-233`). So `pending` is a `ChangedFiles.Known` naming
  exactly the edited file, and line 147 throws it away.
- **The annotation baseline is intact.** It is captured once at provision
  (`QuickBuildSessionManager.kt:620`) and swapped only on a successful rebaseline
  (`:838`). Nothing on the `DaemonDied -> Degraded -> RespawnDaemon` path touches it.
- **On-disk sources are intact** — the daemon is a separate child JVM; only its
  in-memory incremental universe is lost.

`AnnotationImpactAnalyzer.escalation(listOf(theEditedFile))` would have answered
"safe" for a body-only edit (its documented case 2, `AnnotationImpact.kt:56-60`).
The code never asks: `classify` returns at line 47 before reaching the per-file
call at `ChangeClassifier.kt:111-113`.

The reinstall follows mechanically: `FullGradleBuild` -> `InvalidationDetected` ->
`RunFullGradleRebaseline` -> `QuickBuildSessionManager.rebaseline()`, whose own
comment at `:829-830` says the rebaseline "regenerated setup.json and
**reinstalled the test app**".

### How it was found

Task #32's online Room/KSP device run.
`quick-build/corpus/results/20260728T113213Z-task32-roomksp-online/DEVICE-FINDINGS.md:46-57`
names `ChangeClassifier.kt:47` and records **3/3 reproductions** (attempts 3, 4,
and the warm-up). Machine-readable in `events-E1-dbbody.jsonl`:

```
build_started route=CodeOnly -> build_finished outcome=InfrastructureFailure (2 ms later)
-> state Degraded -> state Ready -> invalidation ANNOTATION_PROCESSOR_INPUT_CHANGED
-> Invalidated -> Provisioning -> rebaseline ok=false durationMillis=198047
```

State transitions in `logcat2-E1-dbbody-escalation.txt:47` and `:440-442`.
Corroborating trigger (trim-memory teardown killing the daemon when the user
launches the test app): `20260728T090036Z-task62-trim-teardown/DEVICE-FINDINGS.md:53`.

### How bad

Frequency: **high in AP projects.** Room and KSP are the mainstream Android data
stack; the daemon-death trigger is normal user behaviour (switch to the test app,
come back). 3/3 when attempted.

Severity: **high.** It converts the feature's core promise into its opposite —
a two-second loop becomes a three-minute one, and it drags in defect 2 as a
follow-on, so the user is often left with a dead fast path afterwards.

### Options

1. **Reclassify from the preserved set instead of `Unknown`.** The known set is
   right there at `BuildOrchestrator.kt:147`; keep it and let the per-file
   annotation escalation decide. This is the correct fix and it is small.
   Risk: the union semantics exist for a reason — an *unenumerable* change (a
   watcher overflow, a batch we could not name) genuinely must escalate. The fix
   must distinguish "we lost the daemon" from "we lost track of the files", which
   today are the same `Unknown`.
2. **Split the sentinel.** Introduce a distinct `DaemonRestarted` marker that
   forces a full *compile* (the daemon's caches are cold, so nothing is
   incremental) without forcing a *Gradle rebaseline*. More explicit than option
   1 and harder to get subtly wrong later; slightly more code.
3. **Reduce the frequency instead — keep the daemon alive.** Partly landed
   already (the trim policy no longer tears down on `UI_HIDDEN`). Does not fix
   the escalation, just makes it rarer, and it is exactly the case where the user
   is most likely to be watching.
4. **Do nothing, document it.** Defensible only if the escalation were rare. It
   is not, in the project shape most likely to be used.

---

## 2. After a rebaseline reinstall, the fast path is dead until the user relaunches the test app

**Task #88.**

### What the user experiences

They confirm the reinstall, the rebaseline succeeds, the session says Ready — and
every subsequent save fails with **"Test app is not connected"**. It keeps
failing. Nothing on screen suggests that relaunching their app is the fix, and
the message reads as an infrastructure fault rather than an instruction.

### Mechanism

The connection is the test app's *outbound* AIDL binding into CoGo, not a socket
and not CoGo's own service. `QuickBuildHostService.kt:47-52` installs a death
recipient on it; a reinstall kills the test-app process, that fires, and
`TestAppConnections.onDisconnected()` (`TestAppConnections.kt:66-69`) sets
`_target.value = null`. That null is what `DeployChannel.kt:105` reads:

```kotlin
val connection = connections.target.value ?: return DeployResult.NotConnected
```

which becomes the user-visible string at `QuickBuildExecutorImpl.kt:593-595` and
`SessionFailure.DeployError` at `QuickBuildSessionManager.kt:980`. The reducer
(`QuickBuildSession.kt:420-422`) returns to `Ready` with the failure attached, so
the session stays live and repeats the failure on every save.

**Only the test app can re-establish the binding**, from
`QuickBuildRuntime.onActivityCreated` (`QuickBuildRuntime.java:225-227`) — which
requires one of its Activities to be created, i.e. the app must be launched. The
runtime's rebind/backoff (`QuickBuildClient.java:175-193`) only helps a *running*
process; a killed one has nothing to retry with.

**CoGo never relaunches it after a rebaseline.** `rebaseline()`'s success branch
(`QuickBuildSessionManager.kt:827-859`) restarts the daemon, swaps setup, layout,
executor and annotation baseline, resets `lastDeployedGeneration` — and makes no
`launcher.launch(...)` call. The injected `TestAppLauncher` is used on exactly one
path, the restart-deploy at `QuickBuildExecutorImpl.kt:525`.

**Correction worth carrying into the fix:** there *is* a launcher button on the
same toolbar — the generic, pre-existing `LaunchAppAction` ("Launch app",
`LaunchAppAction.kt:42`, registered `EditorActivityActions.kt:105`). Because
Quick Build installs under the project's real applicationId, it would work. It is
just not part of the Quick Build session UI and nothing connects it to this
failure. So the fix may be "wire up / auto-invoke what exists", not "build a
launcher". The Quick Build menu itself
(`app/src/main/res/menu/menu_quick_build.xml`) has exactly four items — build,
standard run, restart session, help — and no relaunch.

### How it was found

`20260728T113213Z-task32-roomksp-online/DEVICE-FINDINGS.md:58-61`:
"After a rebaseline reinstall, deploys fail until the test app is relaunched...
The session stays Ready with lastFailure set; nothing relaunches the app."
`events-E3-postrebase.jsonl` shows buildIds 3 and 4 both `DeployFailure`.
Causality is visible in one file: the reinstall lands at
`logcat-full-postrun.txt:9982` (`changeType(REPLACED)`, 12:03:42.507) and the two
deploy failures follow at 12:04:02 and 12:04:06 (`:18012`, `:19441`).
Independently reproduced in `20260728T090036Z-task62-trim-teardown/logcat-verify-D-after-edit.txt:669`
and `20260728T080856Z-task67-seed-honesty/DEVICE-FINDINGS.md:21`.

### How bad

Frequency: **deterministic.** Every rebaseline that reinstalls leaves the session
in this state. Since defect 1 causes unnecessary rebaselines, the two compound.

Severity: **high.** The fast path is silently dead and the message misattributes
the cause. A user with no model of the binding has no way to guess the fix.

### Options

1. **Relaunch the test app automatically after a successful rebaseline reinstall.**
   The launcher is already injected into the session manager and already used on
   another path. Smallest change. Tradeoff: it pulls the test app to the
   foreground unasked, which is wrong if the user reinstalled and then went back
   to editing — arguably it should launch only when the next deploy needs it.
2. **Deploy-time recovery: on `NotConnected`, launch and retry once.** Fixes this
   *and* the organic-crash case (defect 4) with one mechanism, and only acts when
   the app is actually needed. Needs a bounded retry and a clear failure if the
   launch itself does not produce a binding.
3. **Fix the message and offer the action.** Keep the behaviour, say "Your app
   isn't running — launch it to keep using Quick Build" with a Launch affordance
   in the flashbar. Honest and cheap, but leaves an interruption in the loop that
   the product does not need to have.
4. **Do nothing, document it.** The workaround is one tap on a button that
   already exists — but only once you know that, which nothing tells you.

---

## 3. A failed or discarded respawn parks the session in `Degraded`, where the Quick Build tap does nothing

**Task #89. Code-substantiated only — see "How it was found".**

### What the user experiences

The toolbar icon turns into a red alert bolt. They tap Quick Build. Nothing
happens — no build, no error, no message, not even a state change. Tapping again
does nothing. The only things that recover the session are "Restart session" from
the dropdown, or an invalidating edit that happens to force a rebaseline.

### Mechanism

`Degraded` is entered on `DaemonDied` (`QuickBuildSession.kt:387-392` from
Ready/Deployed, `:450-455` from Building), always with a `RespawnDaemon` effect.
`QuickBuildSessionManager.respawnDaemon()` (`:908-949`) has three exits that never
dispatch `DaemonRespawned`:

- superseded before start (`:910-915`) — `log.info` only;
- superseded mid-start (`:917-930`) — `log.info` only;
- hard failure (`:941-947`) — `surfaceUserMessage`, so this one at least speaks.

In `reduceDegraded` (`QuickBuildSession.kt:514-548`), `QuickBuildTapped` has **no
arm** and falls into the catch-all at `:545-547`:

```kotlin
else -> {
	SessionTransition(state)
}
```

with `effects` defaulting to empty (`:238-241`), so no `TriggerQuickBuild` is
produced. The tap is fully swallowed — including by `dispatch` (`:434-441`),
which only logs when the state changed. There is no
`degraded + QuickBuildTapped` test in `SessionReducerTest.kt`.

The comment at `QuickBuildSessionManager.kt:944-945` — *"the next explicit tap or
session restart retries"* — **is factually wrong given that reducer.** Only the
restart works.

**Two corrections to how this defect has been framed:**

- **`shrinkDaemonForMemory()` does not no-op during a mid-start respawn; it is a
  *cause* of the problem.** Its guard (`QuickBuildSessionManager.kt:407`) is on
  `Building`, not on a respawn in flight. In `Degraded` it proceeds, bumps
  `daemonEpoch` (`:412`) and shuts the daemon down — and that bump is exactly what
  makes the in-flight respawn hit the mid-start guard at `:917` and discard
  itself. So a `TRIM_MEMORY_RUNNING_CRITICAL` arriving during a respawn is a way
  to strand the session in `Degraded` permanently, with only a `log.info`. The
  deferred-teardown collector at `:259-266` re-fires on every state change, which
  raises the odds of landing that race.
- **Something *is* shown, just not enough.** `Degraded` maps to
  `QuickBuildStatus.Reconnecting` -> `QuickBuildTone.ATTENTION` -> a red
  `ic_quick_build_alert` (`QuickBuildAction.kt:104-121`). But `Reconnecting`
  carries no text anywhere in the repo — no banner, no string resource — so the
  user sees an alert with no explanation, no indication that their tap did
  nothing, and no hint that "Restart session" is the escape.

Exits, exhaustively: `DaemonRespawned` -> Ready (exactly what a discarded respawn
never sends); `SessionRestartRequested` -> Idle; `InvalidationDetected` ->
Invalidated. `DaemonDied` and `ExternalBuildCompleted` both stay Degraded — the
latter is a *conditional* third exit, since `reseedBaseline()` only dispatches
`InvalidationDetected` when the setup artifacts are missing
(`QuickBuildSessionManager.kt:895-900`). Note the invalidation exit is reachable
without user intent: `onWatcherBatch` (`:425-431`) bypasses the reducer, so a
gradle/manifest save while Degraded still forces a rebaseline — while a plain code
save builds against the dead daemon, fails, and re-enters Degraded. A silent loop.

### How it was found

Code reading. The `Degraded` entry and a *successful* exit appear in
`20260728T113213Z-task32-roomksp-online/logcat2-E1-dbbody-escalation.txt:47, :440`,
but **no device log in any `20260728T*` run contains "respawn superseded",
"respawn outlived", or "could not be restarted"** — the stranded case is not
reproduced in the captured evidence. Treat the frequency below as an estimate.

### How bad

Frequency: **unknown, probably low** `[inferred]` — it needs a respawn to fail or
to race a memory-trim. But the swallowed tap is not race-dependent: *any* time the
session is Degraded, taps do nothing.

Severity: **high when it happens** — the session is unusable and the only recovery
is a menu item the user has no reason to associate with a red icon.

### Options

1. **Give `Degraded` a `QuickBuildTapped` arm that re-issues `RespawnDaemon`.**
   A few lines plus a reducer test. Directly makes true the comment that is
   currently false. Needs a guard against stacking respawns.
2. **Say something.** A message on discard (not just `log.info`) and text for
   `Reconnecting`. Cheap and independently valuable — right now the icon is the
   entire vocabulary.
3. **Serialize the daemon lifecycle.** A lifecycle mutex in `DaemonProcessClient`
   so shutdown and respawn cannot interleave, removing the epoch race rather than
   handling it. Bigger, and the epoch guards exist because that ordering is
   genuinely hard; but it addresses the cause.
4. **Auto-retry the respawn with backoff.** The code deliberately does not
   (`:944-945`: "auto-retry loops on a hard-broken daemon would spin"), which is
   a fair reason. A bounded retry — two attempts, then park with a message —
   keeps the reasoning and removes the dead end.

---

## 4. An organic test-app crash never reaches the crash surface

**Task #91.**

### What the user experiences

Their app crashes on its own — a null pointer in a click handler, an OOM kill, a
swipe-away. CoGo shows nothing; the toolbar still says the session is up to date
at generation N. On their next save, the build compiles fine and then fails with
**"Test app is not connected"** — a deploy-infrastructure message for what was
actually a crash in their code, with no stack summary and no offer to relaunch.

### Mechanism

The runtime's uncaught-exception handler reports a crash only while a reload is
in flight. `quickbuild-runtime/.../QuickBuildRuntime.java:303-313`:

```java
public void uncaughtException(Thread thread, Throwable error) {
	try {
		long pending = pendingReloadGeneration;   // 305
		if (pending >= 0) {                       // 306
			client.reportCrash(pending, summarize(error));
		}
	} catch (Throwable ignored) { }
	if (previous != null) { previous.uncaughtException(thread, error); }
}
```

`pendingReloadGeneration` is set only inside `handlePayload` (`:205`) and cleared
on the first resumed frame (`:235`) or in `failReload` (`:289`). Between builds it
is `-1`, so line 306 is false, there is no `else`, and the throwable passes
straight to the previous handler. `failReload`'s two call sites (`:221`, `:405`)
are both inside a deploy, so there is no second route.

**The death *is* detected — it is just routed nowhere.** CoGo holds a binder death
recipient (`QuickBuildHostService.kt:45-52`), and `TestAppConnections.onDisconnected()`
(`:66-69`) nulls the target and emits `TargetReport.Disconnected`. But the session
manager's collector only listens for one thing,
`QuickBuildSessionManager.kt:233-239`:

```kotlin
connections.reports.collect { report ->
	if (report is TargetReport.Crashed) {      // 235
		dispatch(SessionEvent.TestAppCrashed(report.stackSummary))
	}
}
```

`Disconnected` fails that test and is dropped. The reconnect catch-up (`:247-257`)
is gated on `target != null`, so the null transition does nothing there either.
`SessionEvent.TestAppCrashed` is reachable only from `TargetReport.Crashed`, which
is reachable only from the runtime's `reportCrash`, which is gated off at line 306.

Worth knowing for the fix: even when `TestAppCrashed` *does* fire, the crash
summary is never shown. It reaches `Ready(lastFailure = TestAppCrash(...))` ->
`QuickBuildStatus.Failed` -> `ATTENTION` -> the same red icon. No
`surfaceUserMessage` call exists on that path. **The entire user-visible
consequence of a crash today is an icon colour.**

### How it was found

Code reading, plus the downstream symptom is the same "Test app is not connected"
captured for defect 2 (`20260728T113213Z-task32-roomksp-online`). No run
deliberately crashed a test app between builds, so the specific path is
`[unmeasured]` on device.

### How bad

Frequency: **as often as user code crashes** — which, in a live-reload tool aimed
at people iterating quickly, is often.

Severity: **moderate, but the failure is a misdiagnosis rather than a stall.**
The user is told the wrong thing about their own bug, and the information needed
to tell them the right thing (the stack summary) was thrown away.

### Options

1. **Route `Disconnected` to the session.** Add a `TargetDisconnected` event so
   the status reflects "your app isn't running" instead of staying stale.
   Small. Does not recover the stack trace, but fixes the lie.
2. **Report crashes unconditionally from the runtime.** Drop the `pending >= 0`
   gate and report with a sentinel generation. Gets the real crash summary to
   CoGo. Tradeoff: the reducer must not treat an organic crash as a *reload*
   failure — a reload-attributed crash means "the payload we just sent broke your
   app", which is a different and stronger claim.
3. **Surface the summary at all.** Whichever route delivers it, show it — a
   flashbar with the exception line. Today even a reload crash shows nothing but
   a red icon, so this is worth doing on its own.
4. **Fold into defect 2's option 2** (launch-and-retry on `NotConnected`). One
   mechanism covers both "reinstalled" and "crashed", at the cost of not
   explaining which happened.

---

## 5. The install-confirm UX: 180 s of silence, then a misleading message nobody sees

**Task #90.**

### What the user experiences

A rebaseline needs to reinstall the test app. If CoGo is in the foreground the
confirm dialog appears and this is fine. If CoGo is **backgrounded** — which is
the normal middle of the Quick Build loop, since the user switches to their app
to look at it — no dialog appears. Quick Build then waits **180 seconds** in
silence and finally flashes:

> "Test app install was not confirmed within 180s. Tap Quick Build to retry."
> [ Dismiss ]

on a screen the user is not looking at. The message says they failed to confirm
something that was never shown to them, the only button is Dismiss, and when they
do return, a foreground auto-retry (landed 2026-07-27) re-runs the *entire Gradle
rebaseline*.

### Mechanism

**The stated mechanism in this repo is wrong, and it is worth correcting because
it points the fix in the wrong direction.** Five places in the tree
(`QuickBuildSession.kt:170-174` and `:493-496`, `QuickBuildSessionManager.kt:284-286`,
`BuildRoute.kt:89-92`, `ProjectHandlerActivity.kt:633-636`) plus commit
`fe949a9f0`'s message all say Android never delivers the `PENDING_USER_ACTION`
broadcast to a backgrounded app. It does.

- The status callback is an **explicit** broadcast to CoGo's own manifest-declared,
  exported receiver (`ApkInstaller.kt:181-201`, `setClass` + `setPackage` +
  `FLAG_RECEIVER_FOREGROUND`; `AndroidManifest.xml:225-232`). No background-broadcast
  restriction suppresses that, and `InstallationResultReceiver.onReceive` posts to
  EventBus unconditionally.
- This repo's own device evidence says so:
  `20260728T055942Z-task80-foreground-retry-run2/DEVICE-FINDINGS.md:31-35` — *"its
  PENDING_USER_ACTION broadcast was deferred, not dropped: Android delivered it
  when CoGo foregrounded"* `[measured on a56]`.

What actually breaks is two things, both on our side:

1. **The subscriber that owns the dialog is lifecycle-bound.** The only code that
   can launch the confirm activity is `InstallationResultHandler.onResult`
   (`:59-73`, `context.startActivity(intent)` at `:69`) and its signature requires
   an `Activity` (`:40`). Its caller is `BaseEditorActivity.onInstallationResult`
   (`:588-595`), and EventBus registration is `onStart`/`onStop`-scoped
   (`BaseIDEActivity.kt:72-84`). Backgrounded, the editor is stopped, the
   subscriber is unregistered, and the non-sticky post lands with zero subscribers.
2. **A background `startActivity` would be blocked anyway** on Android 10+.

The quick-build-side listener is *not* lifecycle-bound (`InstallationEventFlow`,
registered at DI construction, `QuickBuildModule.kt:87`) and **does** receive the
event while backgrounded — it simply discards it. `TestAppInstaller.kt:59-60`
defines terminal as SUCCESS or FAILURE only, and the collector at `:133-137` waits
for `broadcast.isTerminal`. So `PENDING_USER_ACTION` is observed and thrown away,
and the installer sits in `withTimeoutOrNull` (`:150`,
`DEFAULT_TIMEOUT_MILLIS = 180_000L` at `:211`).

**The message conflates three different situations.** One string
(`TestAppInstaller.kt:170-173`, surfaced at `QuickBuildSessionManager.kt:875-876`)
covers: (i) dialog shown, user walked away; (ii) dialog shown, user cancelled
without a timely FAILURE_ABORTED; (iii) dialog never launched. The type's own
KDoc (`:74-83`) admits it. In case (iii) the sentence is simply untrue.

**No notification-based confirm path exists.** Grepping `NotificationManager`,
`NotificationCompat`, and `notify(` across the whole install path and the
`quick-build` module returns zero hits.

**What the 2026-07-27 fix (`fe949a9f0`) does and does not do.** It adds
`SessionEvent.HostForegrounded`, folds it into the existing `awaitingRetry` arm in
`reduceInvalidated` (`QuickBuildSession.kt:487-505`), and calls it from
`ProjectHandlerActivity.onResume`. It does **not** touch the install path at all —
`TestAppInstaller`, `InstallationResultHandler`, `ApkInstaller` and
`QuickBuildInstallAdapters` are unchanged. It re-runs the whole Gradle rebaseline
on the next resume and hopes the install prompts that time. It covers only
`Invalidated(awaitingRetry = true)`; the **provisioning** path still collapses a
confirmation timeout into a hard failure (`GradleQuickBuildProvisioner.kt:69`), so
a first-tap provision that times out backgrounded dies to Idle. And it has no
max-attempts guard — `onResume` fires on every editor resume, so a user who
deliberately declines gets a fresh rebaseline and prompt every time they come
back. The commit says the rest was deferred deliberately.

### How it was found

Three device runs, task #80 `[measured on a56, 2026-07-28]`:

- `20260728T051050Z-task80-retry-ui-verify` — the park, captured with the rendered
  flashbar text: `ui-toolbar.xml` line 1 holds
  `text="Test app install was not confirmed within 180s. Tap Quick Build to retry."`
  with the only action `text="Dismiss"`. `logcat-P1.txt:3022-3023` shows the park.
  Its P2 (retry after a genuine toolbar tap at (232,322) per `driver.log`) FAILed
  with an empty `events-P2.jsonl` and no session lines in `logcat-P2.txt`.
  Run 2's finding 3 attributes a similar P2 failure to a harness `am start` no-op,
  but that attribution covers the *sibling* run — **this one used a real screen tap
  and its failure is not explained.** Unresolved gap in the evidence chain.
- `20260728T054508Z-task80-foreground-retry` — aborted at P0 because a Samsung FOTA
  `InstallConfirmActivity` stole the foreground during provisioning. That abort is
  itself an instance of the defect: a third-party activity taking focus for a few
  seconds was enough to lose the confirm.
- `20260728T055942Z-task80-foreground-retry-run2` — the one that verified the
  auto-retry. `logcat-excerpt-P2-trigger.txt:1-4` shows
  `Invalidated(awaitingRetry=true) -> ... on HostForegrounded -> Provisioning`.
  Cost of the park: `events-P1-after-timeout.jsonl` records
  `rebaseline ok=false durationMillis=193782` — **193.8 s of dead time before the
  user is told anything.** Recovery: `rebaseline ok=true durationMillis=7553`.
  `screen-P1-midwait-no-dialog.png` is the visual proof that nothing appeared.

**What run 2 did not verify, and this matters:** the retried reinstalls committed
*silently*, without any user action —
`DEVICE-FINDINGS.md:26-31`, "`lastUpdateTime` moved before any dialog
interaction... an update by the same foreground installer needs no confirmation on
this Samsung/Android 16 build". So the mechanism the fix claims (foregrounding
makes the dialog appear) **was never observed**; recovery worked for a different
reason, and both `dialogSeen=True` flags are the stale P1 session's deferred
dialog. Also unverified: the provisioning-path timeout, repeated auto-retry on
repeated resumes, and a user actually declining. `P3` FAILed and was diagnosed as
a harness artifact (mtime-preserving `adb push`), so the foreground-park-then-tap
case was never cleanly measured. And `ui-install-dialog.xml` in that directory is
misnamed — it is CoGo's own post-install "launch the app?" prompt, not the
PackageInstaller confirm; do not cite it as evidence of the confirm UI.

### How bad

Frequency: **every backgrounded rebaseline that reinstalls** — and backgrounding
is the normal middle of the loop, not an edge case.

Severity: **high on the wait, moderate on the outcome.** The session does recover
now, and the never-stale invariant holds. But 180 s of unexplained silence in a
tool whose whole value proposition is a two-second loop is the single worst
user-time cost in this document, and the message that ends it is wrong about what
happened.

### Options

1. **Fail fast and say the truth.** Distinguish "PENDING_USER_ACTION seen but no
   Activity available to show it" from "timed out". The installer already
   receives the broadcast (`QuickBuildInstallAdapters.kt:149-151`) — stop
   discarding it, park immediately with "Your app needs a reinstall — return to
   CoGo to confirm", and skip the 180 s wait entirely. **This is the highest
   value-per-line change in this doc.**
2. **Notification-based confirm.** A notification carrying the confirm intent, so
   the user can approve without switching back. The right end state; costs a
   notification channel, POST_NOTIFICATIONS handling, and a fresh set of OEM
   behaviours to test.
3. **Retry the confirm without re-running Gradle.** The current auto-retry pays a
   full rebaseline for what is really "show that dialog again". Cheaper and
   removes the repeated-rebaseline problem, but needs the install session to be
   re-openable rather than re-created.
4. **Add a max-attempts guard.** Independent of the above, small, and stops the
   prompt-on-every-resume loop for a user who is deliberately declining.
5. **Correct the five wrong comments.** Not a fix, but they will send the next
   person after an Android platform behaviour that is not the problem.

---

## Suggested priority

Ordered by user cost times frequency, not by effort:

1. **Defect 5's fast-fail and honest message** (option 1). Removes 180 s of dead
   silence from a normal path and stops telling the user they failed to do
   something they were never asked to do. Small, self-contained.
2. **Defect 2's launch-and-retry on `NotConnected`** (option 2). Deterministic
   today, and one mechanism also covers defect 4's symptom. Turns "the fast path
   is dead and you must guess why" into a non-event.
3. **Defect 1's reclassification from the preserved changed set.** Turns a
   ~200 s escalation back into a ~2 s edit in the project shape most likely to be
   used. Larger and needs care around the `Unknown` semantics, which is why it is
   third rather than first.
4. **Defect 3's `QuickBuildTapped` arm in `Degraded` plus a message on discard.**
   Cheap, and it makes an in-code comment true that is currently false. The
   lifecycle mutex behind it is a bigger, separable piece of work.
5. **Defect 4's `Disconnected` routing and crash-summary surfacing.** Mostly
   subsumed by item 2 for the *behaviour*; the remaining value is telling the user
   what actually happened, which is worth doing but is the least urgent.

## Ship-blocking for a v1

**Yes — 1, 2, and the fast-fail half of 5.**

The reasoning is the same for all three: each converts the feature's core promise
into its opposite on a path a normal user will hit in an ordinary session, and in
each case the product then explains it wrongly or not at all. Defect 2 is
deterministic after any rebaseline; defect 1 makes rebaselines happen when they
should not, in Room/KSP projects; defect 5 costs three minutes of silence in the
middle of the loop the feature exists to shorten. A user meeting any of these
concludes that Quick Build is unreliable, and they would not be wrong.

**No — 3, 4, and the rest of 5.**

Defect 3 has no device reproduction and needs a race to strand the session; the
tap-swallow is real but cheap to fix later. Defect 4 is a wrong message rather
than a stall, and item 2 removes most of its user-visible effect. The notification
confirm path (5, option 2) is the right end state but is new surface area with
OEM variance — it is a v1.1 feature, not a v1 gate.

Two things this list deliberately does **not** claim to be ship-blocking:
performance (see `perf-roadmap.md` — Quick Build is a median 2.5x faster than an
incremental standard build across 21 apps `[measured on a56]`), and correctness
(the never-stale invariant holds in all five defects). The gap is reliability and
honesty of the feedback, not speed or safety.
