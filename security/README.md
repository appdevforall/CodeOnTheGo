# :security

Instant Hide (ADFA-40): a single, eyes-free way to disguise the IDE for a user who may be in
danger. Any of four triggers swaps Code On The Go to a working offline cryptogram game ("Code
Breaker"), swaps the launcher name/icon, and masks the app in Recents. The disguise persists across
restarts and reboots until the user performs the exit gesture. Instant Hide is **off by default**;
the user arms it in Settings → Security.

## Triggers (when armed)

Owned by `SecurityController`, mounted on `EdgeToEdgeIDEActivity` so they are live on every IDE screen:

- **Three-finger tap** (`GestureDetectorHelper.ThreeFingerTapDetector`, fed from `dispatchTouchEvent`).
- **Screen off** (`ACTION_SCREEN_OFF` broadcast; registered once and kept across `onPause`, since the
  pause races the broadcast).
- **Face-down** — gravity Z past a threshold (orientation only); fires once per transition into face-down.
- **60s inactivity** (a `Handler` reset on every touch).

Any trigger calls `triggerInstantHide()`: set the hidden-state flag, swap to the stealth launcher
alias, launch `CryptogramActivity` (`NEW_TASK | CLEAR_TASK`), finish the IDE. `SplashActivity`
redirects to the disguise on launch while the flag is set. The exit gesture (slow double three-finger
tap) clears the flag, restores the default alias, and returns to the IDE.

## Screenshot / Recents masking

`FLAG_SECURE` is applied whenever Instant Hide is armed — it blocks screenshots and masks the Recents
thumbnail (a single flag; the two can't be separated). There is **no UI toggle**. The only override is
a QA escape hatch: if `/sdcard/Download/CodeOnTheGo.mfs` exists (`SecurityOverride`), `FLAG_SECURE` is
not applied and the app shows a one-time advisory, so QA can capture screenshots and bug reports.

## Pieces

| File | Role |
|---|---|
| `GestureDetectorHelper.kt` | Three-finger tap + slow-double-three-finger-tap detectors (unit-tested). |
| `LauncherAliasManager.kt` | Picks one of five stealth aliases per device (FNV-1a hash); enables/disables aliases. |
| `SecurityController.kt` | Owns the four triggers, the inactivity timer, and the `FLAG_SECURE` policy. |
| `SecurityOverride.kt` | The `CodeOnTheGo.mfs` magic-file check. |
| `CryptogramActivity.kt` + `FlowLayout.kt` + `res/layout/activity_cryptogram.xml` | The disguise game. |

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
