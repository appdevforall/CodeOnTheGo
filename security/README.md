# :security

Instant Hide (ADFA-40): a single, eyes-free way to disguise the IDE for a user who may be in
danger. Any of four triggers swaps Code On The Go to a working offline cryptogram game ("Code
Breaker"), swaps the launcher name/icon, and masks the app in Recents. The disguise persists across
restarts and reboots until the user performs the exit gesture. Instant Hide is **off by default**;
the user arms it in Settings → Security.

## Triggers (when armed)

Owned by `SecurityController`. `InstantHide.register`, called from `IDEApplication`, mounts one on
every activity through `ActivityLifecycleCallbacks` and wraps its window callback to see its input, so
the triggers are live on every screen whatever its base class, the terminal included. The security
Settings screen implements `InstantHide.GesturesExempt`, so arming the feature there does not hide it.

- **Three-finger tap** (`GestureDetectorHelper.ThreeFingerTapDetector`, fed every touch on the window).
- **Screen off** (`ACTION_SCREEN_OFF` broadcast; registered once and kept across `onPause`, since the
  pause races the broadcast).
- **Face-down** (`FaceDownDetector`): gravity Z past a threshold, armed only by a face-up or upright
  reading, so a phone already face down when a screen opens (held overhead, lying on your back) does
  not fire.
- **60s inactivity**: a `Handler` reset by every touch, key and mouse event on the window. When it runs
  out while the soft keyboard is showing or a dialog or popup has the focus, it starts again instead
  of hiding, since those take input the window never sees. A phone left like that still hides when its
  screen times out.

Any trigger calls `triggerInstantHide()`: set the hidden-state flag, swap to the stealth launcher
alias, close the floating windows (the `onHide` hook), mask the notifications (`NeutralNotifications`),
launch `CryptogramActivity` (`NEW_TASK | CLEAR_TASK`), finish the IDE.

While the flag is set, the same lifecycle callbacks send every activity but the disguise to it as it is
created or resumed, so no entry point can surface IDE UI. Entry points whose `onCreate` does work —
`LauncherActivity`, `DeepLinkActivity`, `ExternalFileInstallActivity`, `CrashHandlerActivity` — also
call `InstantHide.redirectIfHidden` and return, so the work never starts. The redirect reuses a running
disguise (`CLEAR_TOP | SINGLE_TOP`), so a link or file opened while hidden does not reset the puzzle.
The exit gesture (slow double three-finger tap) clears the flag, restores the default alias, and
returns to the IDE.

## Notifications

On a hide, `NeutralNotifications.mask` removes the app's ordinary notifications. A foreground
service's cannot be removed while the service runs, so it is replaced under the same id by one that
shows only the disguise's name and a neutral icon. `GradleBuildService` and `TermuxService` build that
neutral notification themselves while the flag is set, so their updates do not bring the IDE's text
back. After the exit gesture each shows its own again on its next update.

## Launcher aliases

The real launcher activity is `LauncherActivity`, with no launcher filter. The default alias is named
`.activities.SplashActivity`, the launcher component from before Instant Hide, so home-screen icons
pinned to it survive the update on every launcher. The five stealth aliases share the target. The
disguise's Recents entry uses this device's stealth alias icon (`LauncherAliasManager.stealthIcon`).

## Screenshot / Recents masking

`FLAG_SECURE` is applied whenever Instant Hide is armed — it blocks screenshots and masks the Recents
thumbnail (a single flag; the two can't be separated). There is **no UI toggle**. The only override is
a QA escape hatch: if `/sdcard/Download/CodeOnTheGo.mfs` exists (`SecurityOverride`), `FLAG_SECURE` is
not applied and the app shows a one-time advisory, so QA can capture screenshots and bug reports.

## Instant Wipe (ADFA-6510)

A separately-armed escalation (its own Settings toggle, off by default, behind a blocking destructive
warning). When armed, a single-finger **circular sweep** (`GestureDetectorHelper.CircleGestureDetector`)
on any surface -- IDE, the disguise, or the terminal -- irreversibly destroys the user's work and
neutralizes the app to the disguise. Instant, no dialog, no undo. The Settings screen is excluded so
arming/practicing cannot wipe.

- **Destroy** (`WipeManager` + `SecureShredder`): cancel the build, stop Termux, close the project and
  the Recents DB, then overwrite-then-delete the source and DB files and fast-delete the bulk
  regenerable caches (`build`/`.gradle`/`.cg`). The overwrite is **best-effort only** -- not a
  guaranteed erase on flash -- so strings say "delete", not "securely erase"; device at-rest encryption
  is the real protection.
- **Neutralize** (`NeutralizationMarker`): after a wipe, `.pip` (filesDir) and `.pip2` (noBackupFilesDir)
  markers are written and the prefs blanked. On launch `SplashActivity` shows the disguise whenever a
  marker is present. **Tamper**: clearing app data strips the markers but the launcher stealth alias
  survives, so a blank-prefs + stealth-alias + no-marker state is treated as tamper and also neutralizes.
  It **does not re-wipe** -- the signature cannot tell a prior wipe from a plain Instant Hide whose data
  was cleared, so re-wiping would risk destroying work never meant for it. The exit gesture is a no-op
  once neutralized.
- **Module boundary**: the engine (`WipeManager`, `SecureShredder`, `CircleGestureDetector`) and the
  markers live in `:security`; the `:app` side (`AppWipeService`, which owns the build service, Termux,
  project manager and Room DB) implements the `WipeService` interface and is reached from `:security`
  via `WipeServiceProvider` on the `Application`.
- **Splash**: the stealth aliases route through `StealthSplashActivity` with a logo-free splash
  (`AppTheme.Splash.Stealth`) so launching the disguise never flashes the Code On The Go logo.
- **`CodeOnTheGo.nowipe`** (`SecurityOverride.isWipeDryRun`): a **debug-only** dev/QA override -- the wipe
  enumerates and logs its targets but deletes nothing (honored only in `BuildConfig.DEBUG`).
- **Practice**: arming either feature opens a safe, full-screen gesture rehearsal
  (`WipePracticeOverlayView` / `HidePracticeOverlayView`) that recognises the real gestures but never
  hides or wipes.

## Pieces

| File | Role |
|---|---|
| `GestureDetectorHelper.kt` | Three-finger tap + slow-double-three-finger-tap (hide) and single-finger circle-sweep (wipe) detectors (unit-tested). |
| `InstantHide.kt` | Mounts a controller on every activity and redirects to the disguise while hidden. |
| `FaceDownDetector.kt` | When a gravity reading completes a flip to face down (unit-tested). |
| `NeutralNotifications.kt` | Removes or neutralises the app's notifications while hidden (unit-tested). |
| `HideState.kt` | The armed and hidden flags, behind an interface tests can replace. |
| `LauncherAliasManager.kt` | Picks one of five stealth aliases per device (FNV-1a hash); enables/disables aliases. |
| `SecurityController.kt` | Owns the hide triggers, the inactivity timer, the `FLAG_SECURE` policy, and the wipe circle detector. |
| `SecurityOverride.kt` | The `CodeOnTheGo.mfs` (FLAG_SECURE) and `CodeOnTheGo.nowipe` (debug-only wipe dry-run) magic-file checks. |
| `CryptogramActivity.kt` + `FlowLayout.kt` + `res/layout/activity_cryptogram.xml` | The disguise game. |
| `WipeManager.kt` + `SecureShredder.kt` | Instant Wipe engine: teardown orchestration + overwrite-then-delete (unit-tested). |
| `WipeService.kt` | `WipeService` / `WipeServiceProvider` boundary interfaces (impl in `:app`). |
| `NeutralizationMarker.kt` | `.pip`/`.pip2` tripwire + the tamper signature (`isWiped`/`isTampered`). |
| `WipePracticeOverlayView.kt` / `HidePracticeOverlayView.kt` + their activities | Safe full-screen gesture rehearsals. |

The on/off flag and hidden-state flag live in `:preferences`
(`preferences/.../internal/SecurityPreferences.kt`, `PreferenceManager`-backed). The Settings UI and
the launcher activity-aliases live in `:app`. User-facing strings live in `:resources`
(`security_strings.xml`) so they reach the Crowdin translation flow.

## ADR 0009 exception (Views, not Compose)

[ADR 0009](../docs/adr/0009-jetpack-compose-for-new-ui.md) requires new IDE UI to be Jetpack Compose.
The cryptogram disguise is a **deliberate, documented exception**: it is disposable camouflage ported
as-is from the Security Mockup prototype, not core IDE UI, and a Compose rebuild was explicitly out of
scope for ADFA-40. It is rendered in Views (XML + `FlowLayout`) with the game logic in the Activity.
It also, by design, omits the long-press help affordance (REVIEW.md §9) — surfacing IDE help on the
"game" would blow the disguise. A future Compose rebuild would be a separate change.

The Instant Wipe / Hide **practice overlays** (`WipePracticeOverlayView`, `HidePracticeOverlayView`)
are also Views under this exception: they do custom `Canvas` finger-trail rendering and feed raw
`MotionEvent`s to the **same** `GestureDetectorHelper` detectors as the real triggers, so the rehearsal
exercises the production recognition exactly. Rebuilding them on Compose's higher-level gesture APIs
would fork that recognition; it is a possible future change, not a goal here.
