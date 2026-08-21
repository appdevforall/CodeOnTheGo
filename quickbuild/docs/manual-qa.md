# Quick Build manual QA

The manual test pass for Quick Build - one phone, one run through every path.

Many of these flows already have automated coverage - JVM unit tests, and in a few cases Kaspresso device tests; however, no automated test can check yet for behavior in the running proxy app.  Plus automated tests and agent `adb` testing both don't always catch usability issues.

Cases are organized in the following groups:

- (A) Core Loop
- (B) Resilience and lifecycle
- (C) Templates and real apps

## Prerequisite Setup Before Testing

1. Use a prepared arm device (Samsung A56 is the default)
  1. No lock screen
  2. Stay-awake while charging. 
  3. ARM processor (Quick Build is ARM only for now)
2. Flags in the device `Download/` folder:
  1. `CodeOnTheGo.exp` - required. Without it, no lightning button.
  2. `CodeOnTheGo.qbbench` - optional; adds the `bench-events.jsonl` session event log.
  3. Flags are read once per process. After creating or deleting any flag file, force-stop CoGo and reopen it.
3. CoGo asks for the install permission during onboarding. If you skipped it, the first provisioning bounces you to a Settings screen and the session quietly reverts to idle. An automated run that cannot tap that Settings toggle can pre-grant it: `adb shell cmd appops set com.itsaky.androidide REQUEST_INSTALL_PACKAGES allow`

## Reading the lightning button

The button is a split button and the session's status display. Every tone has its own icon shape as well as its own colour, so the state reads without relying on colour. There are five.

| Icon                               | Tone         | Session is                                                   |
| ---------------------------------- | ------------ | ------------------------------------------------------------ |
| Solid bolt                         | READY        | No session, or sitting on a successful build                 |
| Stop square spinning inside a ring | BUILDING     | Provisioning, or a build running now. Tapping stops it       |
| Hollow bolt                        | SLOW         | The next build cannot take the fast path and will be a full one. Not a failure |
| Sync arrows                        | RECONNECTING | The compile daemon is being respawned. Transient, resolves itself |
| Bolt with an exclamation mark      | ERROR        | A failure to act on - a failed build, or a daemon respawn that did not come back |

Only ERROR is coloured as a failure. A full rebuild and a daemon respawn are ordinary work, so reading either as an error is a bug, not a finding.

Tap runs Quick Build; the first tap starts the session. Long-press opens a dropdown with three items: Quick Build, Restart session, Help.

### Setup Project (used for tests T1-T16)

1. New Project -> **Basic Activity** template, **Kotlin**
2. Set name `mybasic` (applicationId `com.example.mybasic`). 
3. Let the initial Gradle sync finish before starting T1.

The cases below run in sequence and each builds on the last, so run them in order.

If you need to start over at any point, either run this below, or delete the project in Code on the Go

```bash
adb uninstall com.example.mybasic
adb shell "rm -rf /storage/emulated/0/CodeOnTheGoProjects/mybasic/build \
  /storage/emulated/0/CodeOnTheGoProjects/mybasic/app/build"
```

### Recording the Test

If you wish to record the test, please use the following command

```bash
# start recording (screenrecord caps at 30 min and truncates silently,
# so record in segments rather than one long take)
adb shell screenrecord --time-limit 1740 /sdcard/qa-A.mp4 &

# stop it cleanly from another shell - SIGINT is what finalizes the MP4
adb shell killall -2 screenrecord

# pull it off the device
adb pull /sdcard/qa-A.mp4 . && adb shell rm /sdcard/qa-A.mp4
```

Turn on Developer options -> Show taps first, or the taps are invisible in the recording. A file killed any way other than SIGINT has no `moov` atom and will not play; check the pulled file opens before deleting the device copy.

## Block A - core loop

### T1 - First tap: provisioning

Automated coverage: unit + Kaspresso

Steps:

1. Open `mybasic` and wait for the Gradle sync to finish.
2. Tap the lightning button once.
3. Approve the OS install prompt when it appears (allow up to 180 s for it).

Expected:

1. Build Output narrates each Gradle task as it runs.
2. The install prompt appears.
3. The test app launches, showing "Hello user!" and a floating action button.
4. The lightning button returns to READY (solid bolt).

### T2 - Code-only edit

Automated coverage: Kaspresso

Steps:

1. Open `app/src/main/java/com/example/mybasic/MainActivity.kt`.
2. In the FAB's click handler, change the message literal to `code: B`.
3. Save.
4. Switch to the test app and tap the FAB.

Expected:

1. No install prompt and no dialog.
2. CoGo stays in front - a save never foregrounds the test app.
3. The FAB's message reads `code: B`.
4. The reload takes a few seconds, not a full build.

### T3 - Compile error, never stale

Automated coverage: Kaspresso

Steps:

1. In `MainActivity.kt`, break the syntax - drop a closing quote, or add a stray `}`.
2. Save.
3. Fix the syntax and change the message literal to `code: B2`.
4. Save, then tap the FAB in the test app.

Expected:

1. The status bar reads "Quick Build: BUILD FAILED - see Build Output".
2. Build Output shows the compile error with its file and line.
3. Nothing reloads - the test app keeps running the last good code and still shows `code: B`.
4. The fixing save builds clean and the status clears.
5. The FAB's message moves to `code: B2`.

### T4 - Resources-only edit

Automated coverage: Kaspresso

Steps:

1. In `app/src/main/res/values/strings.xml`, add `<string name="res_label">res: A</string>`.
2. Open the layout holding the "Hello user!" TextView (`app/src/main/res/layout/activity_main.xml` in the current template) and point that TextView's `android:text` at `@string/res_label`.
3. Save, and confirm the label on screen reads `res: A`.
4. Change `res_label`'s value to `res: B`. Save.

Expected:

1. The label becomes `res: B`.
2. No install prompt.
3. No crash on the resource-table relink.

### T5 - Assets-only edit

Automated coverage: Kaspresso

Steps:

1. Create `app/src/main/assets/message.txt` containing `asset: A`.
2. Make the FAB's message read from it: `assets.open("message.txt").bufferedReader().use { it.readText() }`. Save.
3. Change `message.txt` to `asset: B`. Save.
4. Tap the FAB.

Expected:

1. The FAB's message reads `asset: B`.

### T6 - Mixed edit, two routes in one save

Automated coverage: unit

Steps:

1. Change res_label in strings.xml to res: C. Do not save yet.
2. Append `+ " (C)"` to the end of the FAB's message expression - after T5 that expression reads from the asset, so it becomes `assets.open("message.txt").bufferedReader().use { it.readText() } + " (C)"`. Do not save yet.
3. Save both at once with Save all files, so the two writes land in one batch. Two separate manual saves will be two builds - the watcher coalesces changes only 150 ms apart, which no one can hit by hand, so that is expected rather than a failure.

Expected:

1. One build runs, not two.
2. The label reads `res: C`.
3. The FAB's message ends in `(C)`.

### T7 - Rebaseline, full-Gradle fallback

Automated coverage: unit (partial)

Steps:

1. Open `app/build.gradle.kts` and make a harmless change - edit a comment.
2. Save, and approve the reinstall dialog.
3. Change the FAB's message literal again. Save.
4. Tap the lightning button once.

Expected:

1. The save runs a real Gradle build, visibly longer than T2, and never hot-reloads.
2. CoGo stays in the foreground.
3. Narration reads "a full build is needed", then "rebuilding your app" - never "initial full build".
4. After the reinstall, the code save alone does not redeploy.
5. The one tap relaunches the app with the edit deployed.

### T7b - A failed rebaseline recovers on save

Automated coverage: unit (partial)

Steps:

1. In `app/build.gradle.kts`, set `compileSdk` to a version the device does not have - 99. Save.
2. Set it back to its original value. Save, and do not tap anything.

Expected:

1. The failing build parks the session and the icon shows ERROR.
2. A flashbar names the cause.
3. The fixing save retries by itself, with no tap.
4. The session returns to READY.

## Block B - resilience and lifecycle

### T8 - Coalescing of rapid saves

Automated coverage: unit

Steps:

1. Change the FAB's message literal to `A`. Save.
2. Change it to `B` and save, then `C` through `G`, saving each one while the previous build is still running. A warm build takes about a second, so save as fast as you can - saves that arrive mid-build merge into the next one. (At a normal hand cadence you may simply get one build per save; that also passes.)

Expected:

1. Fewer builds run than saves.
2. After the last build, the app shows `G`.

### T9 - Cross-file source dependencies

Automated coverage: unit

Steps:

1. Add `Constants.kt` beside `MainActivity.kt` with `inline fun getLabel(prefix: String) = "$prefix: v1"`.
2. Call it from the FAB's click handler. Tap the lightning button.
3. Change the inline function's body to return `"$prefix: v2"`. Tap the lightning button.
4. Tap the FAB.

Expected:

1. The FAB's message shows the new `v2` text - the edited inline function reaches its call site in the running app.

### T10 - Survives test-app force-kill

Automated coverage: Kaspresso

Steps:

1. Force-stop the test app: `adb shell am force-stop com.example.mybasic`.
2. Change the FAB's message literal. Save.
3. Tap the lightning button.

Expected:

1. The save reports "Your app is not running. Tap Quick Build to start it with your changes."
2. The tap relaunches the app with the edit deployed.

### T11 - Survives CoGo force-kill

Automated coverage: none

Steps:

1. Force-stop CoGo: `adb shell am force-stop com.itsaky.androidide`.
2. Reopen CoGo on `mybasic`.
3. Tap the lightning button.
4. Change the FAB's message literal. Save.

Expected:

1. The test app survives CoGo's death (it keeps running while CoGo is gone). The recovery tap then rebuilds and reinstalls the proxy, replacing the process - expected, since each provision bakes a fresh baseline into the APK.
2. The session re-establishes.
3. The edit deploys.

### T12 - No experiments flag means no Quick Build

Automated coverage: Kaspresso

Steps:

1. Clear the Build Output buffer first - CoGo restores the previous session's output on relaunch, and its lines read exactly like a live run's.
2. Delete the flag: `adb shell rm /storage/emulated/0/Download/CodeOnTheGo.exp`.
3. Force-stop CoGo and reopen it.
4. Open or create a project.
5. When done, restore the flag (`adb shell touch /storage/emulated/0/Download/CodeOnTheGo.exp`) and force-stop CoGo again.

Expected:

1. No lightning button.
2. No Quick Build setup build.
3. No "Quick Build:" line in Build Output - just the plain Gradle sync.

### T13 - One install slot, clobber confirm

Automated coverage: unit + Kaspresso

Steps:

1. With the Quick Build test app installed, press Run. Read the dialog, then confirm it.
2. Tap the lightning button. Read that dialog, then confirm it.

Expected:

1. The Run-ward dialog explains it replaces the proxy app with a regular APK.
2. The Quick-Build-ward dialog explains it replaces the regular APK with a proxy app.
3. Both confirm buttons read Replace.
4. Neither switch clobbers without asking.

### T14 - Stop, restart session, Help

Automated coverage: Kaspresso

Steps:

1. Long-press the lightning button and pick Restart session.
2. Long-press it again and pick Help.

Expected:

1. Restart re-provisions cleanly and faster than T1, with no reinstall unless the app's bytes changed.
2. The icon tracks BUILDING, then READY.
3. Help opens a popup describing Quick Build. Note: the content comes from `documentation.db`, a prebuilt asset owned by the documentation repository - until a row for `EDITOR_TOOLBAR_QUICK_BUILD` ships in it, Help opens the "no tooltip" fallback. That reads as a failure here; the fix is a documentation-repo row, not a code change in this repo.
4. The dropdown has exactly three items: Quick Build, Restart session, Help.

### T15 - Backgrounding, daemon survives

Automated coverage: none

Steps:

1. With the session at READY, press HOME.
2. Wait 2-3 minutes.
3. Return to CoGo, change the FAB's message literal, and save.

Expected:

1. The edit reloads at normal T2 speed. Roughly ten seconds or more means the daemon died and cold-started. Read that timing from the raw recording or the logs, never from a shortened video.

### T16 - Daemon death and respawn

Automated coverage: unit

Steps:

1. Kill the daemon from the Mac: `adb shell "run-as com.itsaky.androidide pkill -f quickbuild-daemon"`.
2. Change the FAB's message literal. Save.

Expected:

1. The session goes Degraded and narrates "the compile daemon stopped; restarting it. Your app keeps running."
2. It respawns and re-seeds with no tap from you.
3. The edit then builds and deploys.

## Block C - templates and real apps (optional tail)

Each case here needs its own project, so each pays its own cold provisioning cost.

### T17 - Java template

Automated coverage: unit

Steps:

1. Create a project from a Java template and run T1 on it.
2. Edit the `"Replace with your action"` Toast literal in `MainActivity.java`. Save.
3. Tap the FAB.

Expected:

1. The new toast text appears - this exercises the javac path.

### T18 - Navigation Component, Bottom Navigation

Automated coverage: unit

Steps:

1. Open a Bottom Navigation project, start a session, and launch the app.
2. Tap through all three tabs.
3. Edit the `"This is home Fragment"` literal in `HomeViewModel.kt`. Save.
4. Look at the Home tab.

Expected:

1. All three tabs open without crashing. A `Fragment$InstantiationException` is a regression.
2. The edit reloads.

### T19 - Compose

Automated coverage: unit (partial)

Steps:

1. Open a Compose project and start a session.
2. Edit a `@Composable` function body. Save.

Expected:

1. The composable recomposes with the edit and never renders stale. The compile half is unit-tested; whether a composable actually recomposes after a hot swap is device-only.

### T20 - Real app, Service restart route

Automated coverage: unit

Steps:

1. Open a real app that declares a Service.
2. Edit the Service class. Tap the lightning button.
3. Edit a helper class the Service calls. Tap the lightning button.

Expected:

1. The Service edit restarts the app process to take effect.
2. The helper-only edit also restarts. The restart rule keys on what the app declares, not on
   what the edit touched: while a Service is declared, every code-bearing deploy restarts
   (`DeployPolicy`; see `live-reload-alternatives.md`).

### T21 - Known-slow path, sora-editor-full

Automated coverage: none

Steps:

1. Wrap and push `sora-editor-full` first - it is not one of the bundled templates.
2. Start a session, make a warm code edit, and save.

Expected:

1. The warm edit deploys correctly. This is the one app where Quick Build currently loses to a standard incremental build, so the reload may be slower than a full build.
