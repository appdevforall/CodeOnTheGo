# Decision: Review five user-facing defects found during testing

## Summary

- Device testing on 2026-07-25..28 surfaced **five user-facing defects**. **None is fixed.** Each is tracked as an ADFA-4128 task (#87-#91). The table below is the decision content; the sections after it are the evidence for each row.
- **Neither speed nor correctness is at stake.** The never-stale invariant holds in all five — nobody ends up running stale code. What the user loses is trust: a fast path that went slow, went dead, or went quiet.
- **Decision: which of these block v1?** Proposed, revised after Bryan's 2026-07-28 review: block on **#88 and #90** — the two that hit every user on every rebaseline. **#87, #89 and #91 go to v1.1.**

**Provenance**: `[measured on a56]` = Samsung A56, dates as noted. Untagged prose is code reading against the working tree at `75483b6eb`.

## The five gaps, and whether they block v1

Sorted in descending priority. **Blocks v1 is TBD on every row** — that is the call this doc is asking the team to make; the summary carries the proposal.

| Task | What breaks                                                  | Frequency                                                    | Severity             | Blocks v1?        |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------------------- | ----------------- |
| #88  | After any rebaseline reinstall, every save fails "Test app is not connected" until the user relaunches their app | Deterministic — every rebaseline reinstall, and rebaseline reaches every user | High                 | TBDRecommend: yes |
| #90  | A backgrounded rebaseline waits 180 s in silence, then shows a message that is wrong about what happened | Every backgrounded rebaseline that reinstalls — and a rebaseline runs long enough that backgrounding is normal | High on the wait     | TBDRecommend: yes |
| #89  | A failed respawn parks the session in `Degraded`, where tapping Quick Build does nothing at all | Code reading only, no device repro; probably low `[inferred]` | High when it happens | TBD               |
| #91  | The user's app crashes on its own; CoGo says nothing and then misattributes it to deploy infrastructure | Code reading only, `[unmeasured]` on device; as often as user code crashes | Moderate             | TBD               |
| #87  | A one-line edit in a Room/KSP project triggers a full ~200 s rebaseline + reinstall | Only in projects using annotation processors — a minority, and QB has no real support for them yet; 3/3 when attempted | High                 | TBD               |

## Why #88 and #90 are proposed as blocking

Both land on a path every user takes — a rebaseline — and in both the product then explains it wrongly or not at all. #88 is deterministic on every rebaseline reinstall. #90 costs 180 s of silence, and a rebaseline runs long enough that backgrounding is the normal thing to do, not the edge case.

A user meeting any of these concludes Quick Build is unreliable, and they would not be wrong.

## Why #87, #89 and #91 do not

- **#87** needs an annotation processor to fire at all — a minority of projects, and Quick Build has no real support for them yet, so the escalation is rarer than the 3/3 reproduction suggests and lands where expectations are already low.
- **#89** has no device reproduction and needs a race to strand the session. **#91** is a wrong message rather than a stall, and fixing #88 removes most of its user-visible effect.
- Within **#90**, the v1 fix is the fast-fail (option 1). The notification-based confirm (option 2) is the right end state but is new surface area with OEM variance — v1.1.

## None of these is in the README's "Known limitations (v1)" list

That list is honest about what Quick Build deliberately does not do. These five are cases where it behaves *worse than its own design intends*, which a user reads as bugs rather than boundaries.

## #87 — A body-only edit escalates to a full rebaseline and a reinstall

**Task #87.**

**What the user sees.**

- They change one line inside a method, in a project using Room, KSP, or any annotation processor.
- Instead of a ~2 s hot reload they get a full Gradle rebaseline — **198 s in the captured run** `[measured on a56, 2026-07-28]`.
- It ends in a package-installer dialog asking them to reinstall their own app.
- The trigger is invisible to them: the compile daemon died between builds, most often because they switched to their test app and the system reclaimed memory.

**Mechanism.**

- Daemon death parks the session in `Degraded` and schedules a respawn.
- On respawn, `BuildOrchestrator.kt:147` re-seeds the pending set with `ChangedFiles.Unknown`, which is an absorbing element (`ChangedFiles.kt:50-55`), destroying the known file set.
- `ChangeClassifier.kt:47` then escalates unconditionally whenever `annotationImpact.active` — which is just `profile.hasProcessors` (`AnnotationImpact.kt:88`), i.e. "does this project configure any processor at all", not "did this edit touch processor input".

**The pessimism is unnecessary, which is what makes it a defect rather than a design choice.** Three things are intact at line 147:

- The known changed-file set still exists — `BuildOrchestrator.kt:348` restores the failed build's batch before `BuildFailed` is dispatched, so `pending` names exactly the edited file.
- The annotation baseline is intact — captured at provision (`QuickBuildSessionManager.kt:620`), swapped only on a successful rebaseline.
- On-disk sources are intact.

`AnnotationImpactAnalyzer.escalation` would have answered "safe" for a body-only edit (`AnnotationImpact.kt:56-60`), but the code returns at `ChangeClassifier.kt:47` before reaching the per-file call at `:111-113`. The reinstall then follows mechanically via `QuickBuildSessionManager.rebaseline()` (`:829-830`).

**Evidence.**

- `corpus/results/20260728T113213Z-task32-roomksp-online/DEVICE-FINDINGS.md:46-57` names `ChangeClassifier.kt:47` and records **3/3 reproductions**.
- The event chain is in `events-E1-dbbody.jsonl`: `route=CodeOnly` -> `InfrastructureFailure` 2 ms later -> `Degraded` -> `ANNOTATION_PROCESSOR_INPUT_CHANGED` -> `rebaseline ok=false durationMillis=198047`.

**Options.**

| #   | Option                                                       | Cost / caveat                                                |
| --- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 1   | Reclassify from the preserved set instead of `Unknown`       | The correct fix and small, but it must distinguish "we lost the daemon" from "we lost track of the files", which today are the same `Unknown`; an unenumerable change genuinely must still escalate. |
| 2   | Split the sentinel: a distinct `DaemonRestarted` marker forcing a full *compile* without a Gradle *rebaseline* | More explicit, slightly more code.                           |
| 3   | Keep the daemon alive more often (partly landed)             | Reduces frequency, does not fix the escalation.              |
| 4   | Document it                                                  | Defensible only if the escalation were rare, and it is not.  |

## #88 — After a rebaseline reinstall, the fast path is dead until the user relaunches

**Task #88.**

**What the user sees.**

- The rebaseline succeeds and the session says Ready.
- Every subsequent save fails with **"Test app is not connected"**.
- Nothing on screen suggests that relaunching their app is the fix, and the message reads as an infrastructure fault rather than an instruction.

**Mechanism.**

- The connection is the test app's *outbound* AIDL binding into CoGo.
- A reinstall kills the test-app process; the death recipient (`QuickBuildHostService.kt:47-52`) fires, `TestAppConnections.onDisconnected()` (`:66-69`) nulls the target, and `DeployChannel.kt:105` returns `NotConnected`, surfaced at `QuickBuildExecutorImpl.kt:593-595`.
- The reducer (`QuickBuildSession.kt:420-422`) returns to `Ready` with the failure attached, so the session stays live and repeats the failure on every save.
- **Only the test app can re-establish the binding**, from `QuickBuildRuntime.onActivityCreated` (`QuickBuildRuntime.java:225-227`) — so the app must be launched; the runtime's rebind/backoff only helps a *running* process.
- **CoGo never relaunches it**: `rebaseline()`'s success branch (`QuickBuildSessionManager.kt:827-859`) makes no `launcher.launch(...)` call, and the injected `TestAppLauncher` is used on exactly one other path (`QuickBuildExecutorImpl.kt:525`).

**Correction worth carrying into the fix.**

- A launcher button already exists on the same toolbar — the generic `LaunchAppAction` (`LaunchAppAction.kt:42`, registered `EditorActivityActions.kt:105`).
- Because Quick Build installs under the project's real applicationId, it would work. So the fix may be "wire up what exists", not "build a launcher".
- The Quick Build menu itself has four items and no relaunch.

**Evidence.**

- `20260728T113213Z-task32-roomksp-online/DEVICE-FINDINGS.md:58-61`.
- Causality visible in one file — the reinstall lands at `logcat-full-postrun.txt:9982` (`changeType(REPLACED)`, 12:03:42.507), the two deploy failures follow at 12:04:02 and 12:04:06.
- Independently reproduced in two other runs.

**Options.**

| #   | Option                                                       | Cost / caveat                                                |
| --- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 1   | Relaunch automatically after a successful rebaseline reinstall | Smallest change, but it pulls the app to the foreground unasked. |
| 2   | **Recover at deploy time: on ****`NotConnected`****, launch and retry once** | Fixes this *and* defect 4's symptom with one mechanism, and only acts when the app is actually needed; needs a bounded retry. |
| 3   | Fix the message and offer a Launch affordance                | Honest and cheap, but leaves an interruption the product does not need. |

## #89 — A failed respawn parks the session in `Degraded`, where the tap does nothing

**Task #89. Code-substantiated only — no device reproduction.**

**What the user sees.**

- The toolbar icon turns into a red alert bolt.
- They tap Quick Build and nothing happens — no build, no error, no state change.
- Only "Restart session", or an invalidating edit that forces a rebaseline, recovers it.

**Mechanism.**

- `QuickBuildSessionManager.respawnDaemon()` (`:908-949`) has three exits that never dispatch `DaemonRespawned`:
  - superseded before start (`:910-915`) — `log.info` only;
  - superseded mid-start (`:917-930`) — `log.info` only;
  - hard failure (`:941-947`), which at least speaks.
- In `reduceDegraded` (`QuickBuildSession.kt:514-548`), `QuickBuildTapped` has **no arm** and falls into the catch-all at `:545-547` with empty effects, so no `TriggerQuickBuild` is produced. The tap is fully swallowed.
- There is no `degraded + QuickBuildTapped` test in `SessionReducerTest.kt`.
- The comment at `QuickBuildSessionManager.kt:944-945` — *"the next explicit tap or session restart retries"* — **is factually wrong given that reducer**; only the restart works.

**Two corrections to how this has been framed.**

- `shrinkDaemonForMemory()` does not no-op during a mid-start respawn — it is a *cause*: its guard (`:407`) is on `Building`, not on a respawn in flight, so in `Degraded` it proceeds, bumps `daemonEpoch` (`:412`), and that bump is exactly what makes the in-flight respawn discard itself at `:917`.
- Something *is* shown — `Degraded` maps to a red `ic_quick_build_alert` (`QuickBuildAction.kt:104-121`) — but `Reconnecting` carries no text anywhere in the repo, so the user sees an alert with no explanation and no hint that "Restart session" is the escape.

**The session can also loop silently.** `onWatcherBatch` (`:425-431`) bypasses the reducer, so:

- a gradle/manifest save while Degraded still forces a rebaseline;
- a plain code save builds against the dead daemon, fails, and re-enters Degraded.

**Evidence.** Code reading.

- No device log in any `20260728T*` run contains "respawn superseded", "respawn outlived", or "could not be restarted" — the stranded case is not reproduced in captured evidence, so treat the frequency as an estimate `[inferred]`.
- The swallowed tap, however, is not race-dependent: any time the session is Degraded, taps do nothing.

**Options.**

| #   | Option                                                       | Cost / caveat                                                |
| --- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 1   | Give `Degraded` a `QuickBuildTapped` arm re-issuing `RespawnDaemon` | A few lines plus a reducer test; needs a guard against stacking respawns. |
| 2   | Say something: a message on discard, and text for `Reconnecting` | —                                                            |
| 3   | Serialize the daemon lifecycle with a mutex so shutdown and respawn cannot interleave | Bigger, but addresses the cause.                             |
| 4   | A bounded retry (two attempts, then park with a message)     | Keeps the reasoning behind today's deliberate no-auto-retry while removing the dead end. |

## #91 — An organic test-app crash never reaches the crash surface

**Task #91.**

**What the user sees.**

- Their app crashes on its own. CoGo shows nothing and still reports the session up to date.
- On their next save the build compiles fine and then fails with **"Test app is not connected"** — a deploy-infrastructure message for what was actually a crash in their code, with no stack summary.

**Mechanism.**

- The runtime reports a crash only while a reload is in flight: `QuickBuildRuntime.java:306` gates on `pendingReloadGeneration >= 0`, which is set only inside `handlePayload` (`:205`) and cleared on the first resumed frame (`:235`). Between builds it is `-1`, there is no `else`, and the throwable passes to the previous handler.
- **The death *****is***** detected — it is just routed nowhere:** `TestAppConnections.onDisconnected()` emits `TargetReport.Disconnected`, but the session manager's collector (`QuickBuildSessionManager.kt:235`) only tests for `TargetReport.Crashed` and drops everything else.

**Worth knowing for the fix.** Even when `TestAppCrashed` *does* fire, the summary is never shown — it reaches `Ready(lastFailure = TestAppCrash(...))` and then the same red icon, with no `surfaceUserMessage` on that path. **The entire user-visible consequence of a crash today is an icon colour.**

**Evidence.** Code reading.

- The downstream symptom is the same "not connected" captured for defect 2.
- No run deliberately crashed a test app between builds, so this specific path is `[unmeasured]` on device.

**Options.**

| #   | Option                                                       | Cost / caveat                                                |
| --- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 1   | Route `Disconnected` to the session as a `TargetDisconnected` event | Small, fixes the lie, does not recover the stack.            |
| 2   | Report crashes unconditionally from the runtime with a sentinel generation | Gets the real summary across, but the reducer must not treat an organic crash as a *reload* failure, which is a different and stronger claim. |
| 3   | Surface the summary at all                                   | —                                                            |
| 4   | Fold into defect 2's option 2                                | Costs not explaining which happened.                         |

## #90 — The install-confirm UX: 180 s of silence, then a misleading message

**Task #90.**

**What the user sees.**

- A rebaseline needs to reinstall the test app.
- If CoGo is backgrounded — the normal middle of the loop, since the user switches to their app to look at it — no dialog appears.
- Quick Build waits **180 seconds** in silence, then flashes *"Test app install was not confirmed within 180s. Tap Quick Build to retry."* with a single Dismiss button, on a screen the user is not looking at.
- The message says they failed to confirm something never shown to them.

**The stated mechanism in this repo is wrong, and it points the fix in the wrong direction.**

- Five places (`QuickBuildSession.kt:170-174` and `:493-496`, `QuickBuildSessionManager.kt:284-286`, `BuildRoute.kt:89-92`, `ProjectHandlerActivity.kt:633-636`) plus commit `fe949a9f0` say Android never delivers the `PENDING_USER_ACTION` broadcast to a backgrounded app.
- It does — the callback is an explicit broadcast to CoGo's own manifest-declared receiver (`ApkInstaller.kt:181-201`, `AndroidManifest.xml:225-232`).
- This repo's own evidence records it being *deferred, not dropped* (`20260728T055942Z-task80-foreground-retry-run2/DEVICE-FINDINGS.md:31-35`) `[measured on a56]`.

**What actually breaks is on our side.**

- The subscriber that owns the dialog is lifecycle-bound: only `InstallationResultHandler.onResult` (`:59-73`) can launch the confirm activity, it requires an `Activity` (`:40`), and EventBus registration is `onStart`/`onStop`-scoped (`BaseIDEActivity.kt:72-84`) — so backgrounded, the non-sticky post lands with zero subscribers.
- A background `startActivity` would be blocked on Android 10+ anyway.
- Meanwhile the quick-build-side listener is *not* lifecycle-bound (`QuickBuildModule.kt:87`) and **does** receive the event — it simply discards it, because `TestAppInstaller.kt:59-60` defines terminal as SUCCESS or FAILURE only, leaving the installer in `withTimeoutOrNull` (`:150`, `DEFAULT_TIMEOUT_MILLIS = 180_000L` at `:211`).
- **No notification-based confirm path exists** anywhere in the install path.

**The message also conflates three situations** (`TestAppInstaller.kt:170-173`):

- dialog shown and user walked away;
- dialog shown and cancelled;
- dialog never launched — in this one the sentence is simply untrue.

**What the 2026-07-27 fix (****`fe949a9f0`****) does and does not do.**

- It adds `SessionEvent.HostForegrounded` and re-runs the whole Gradle rebaseline on the next resume.
- It does **not** touch the install path at all.
- It covers only `Invalidated(awaitingRetry = true)` — the provisioning path still collapses a confirmation timeout into a hard failure (`GradleQuickBuildProvisioner.kt:69`).
- It has no max-attempts guard, so a user who deliberately declines gets a fresh rebaseline and prompt on every resume.

**Evidence, and its limits.** Three device runs, task #80 `[measured on a56, 2026-07-28]`.

| what                                      | value                                                        |
| ----------------------------------------- | ------------------------------------------------------------ |
| Park captured with rendered flashbar text | `20260728T051050Z-task80-retry-ui-verify`                    |
| Cost of the park                          | `rebaseline ok=false durationMillis=193782` — **193.8 s of dead time before the user is told anything** |
| Recovery                                  | 7.6 s (`20260728T055942Z-task80-foreground-retry-run2`)      |

**What run 2 did not verify, and this matters:**

- The retried reinstalls committed *silently*, with no user action, because an update by the same foreground installer needs no confirmation on that Samsung/Android 16 build. So the mechanism the fix claims — foregrounding makes the dialog appear — **was never observed**; recovery worked for a different reason.
- Also unverified: the provisioning-path timeout, repeated auto-retry on repeated resumes, and a user actually declining.

**Options.**

| #   | Option                                                       | Cost / caveat                                                |
| --- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 1   | **Fail fast and say the truth** — the installer already receives the broadcast, so stop discarding it, park immediately with "Your app needs a reinstall — return to CoGo to confirm", and skip the 180 s wait entirely | **Highest value per line in this document.**                 |
| 2   | Notification-based confirm                                   | The right end state; costs a notification channel, POST_NOTIFICATIONS handling, and a fresh set of OEM behaviours to test. |
| 3   | Retry the confirm without re-running Gradle                  | Needs the install session to be re-openable rather than re-created. |
| 4   | Add a max-attempts guard                                     | Small and independent.                                       |
| 5   | Correct the five wrong comments                              | So the next person does not chase an Android platform behaviour that is not the problem. |
