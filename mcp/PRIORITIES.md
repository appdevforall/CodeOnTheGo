# cogo-mcp tool priorities

Supersedes `TODO.txt`. Scored 2026-08-11 against a real emulator, not from
reading the source alone -- several entries changed once measured.

## Rubric

**Score = 4·Value + 3·Reach + 2·Verify + 2·Unblocks + 1·Safety** (max 60)

### Value (x4) - does this serve the reason the MCP exists?

The MCP exists to let an agent *exercise the IDE*, not to build software. A tool
that enables testing a feature -- including a destructive one -- scores as value,
not as risk.

| 5 | Core loop. An agent can do little without it. |
| 3 | Real workflow, off the critical path. |
| 1 | Completeness. Rarely needed. |

### Reach (x3) - how much machinery does it take?

Scored *assuming prerequisites are met*; dependencies are the `PREREQ` flag, so
they are not counted twice.

| 5 | Exported activity, or a pure file/preference operation with no UI at all. |
| 4 | A single wired keyboard shortcut. |
| 3 | Shortcut plus a tap or two. |
| 2 | Multi-step UI within `MainActivity`. |
| 1 | Long form-filling, or deep inside `EditorActivityKt`. |

### Verify (x2) - can the tool prove it worked?

| 5 | Deterministic output we can assert (a file list). |
| 4 | `dumpsys` confirms the resumed activity. |
| 2 | Needs a UI-tree dump -- same activity either way. |
| 1 | No reliable signal; success would be an assumption. |

### Unblocks (x2)

| 5 | Gates a cluster. | 3 | Unblocks one or two. | 1 | Leaf. |

### Safety (x1, inverted)

Scores **the tool's own effect**, never the screen's potential. A read-only tool
that opens a dangerous screen is a 5.

| 5 | Read-only. | 3 | Writes recoverable state. | 1 | Destroys user data. |

### Tie-breakers

1. Prefer the cheapest thing that teaches us something new.
2. Prefer read-only over mutating.
3. Prefer a natural test seam over device-only verification.

**Deliberately not a dimension:** "similar to something we already built."
Grouping the six preference screens invites building an abstraction before the
duplication is real. Score each alone; if a seam emerges after the second or
third, refactor then.

## Scores

| Score | # | Task | V | R | Ve | U | S | Flags |
|---|---|---|---|---|---|---|---|---|
| 58 | 18 | List pre-existing projects | 5 | 5 | 5 | 4 | 5 | |
| 56 | 23 | List files in current project | 5 | 5 | 5 | 3 | 5 | PREREQ |
| 54 | 17 | List available project templates | 4 | 5 | 5 | 4 | 5 | |
| 51 | 19 | Open a specific project | 5 | 3 | 4 | 5 | 4 | |
| 49 | 6 | Navigate to Main Preferences | 4 | 4 | 4 | 4 | 5 | |
| 44 | 5 | Navigate to Home Termux | 3 | 5 | 4 | 2 | 5 | |
| 43 | 1 | Navigate to new project | 4 | 4 | 2 | 3 | 5 | |
| 43 | 2 | Navigate to open saved project | 4 | 4 | 2 | 3 | 5 | |
| 42 | 20 | Create a project from a template | 5 | 1 | 4 | 4 | 3 | |
| 42 | 24 | Save the current project | 4 | 4 | 3 | 2 | 4 | PREREQ |
| 41 | 16 | Move feedback button | 2 | 5 | 4 | 3 | 4 | setup primitive |
| 37 | 3 | Navigate to clone a git project | 3 | 4 | 2 | 2 | 5 | |
| 37 | 4 | Navigate to delete a saved project | 4 | 2 | 2 | 3 | 5 | gates destructive testing |
| 35 | 25 | Close the current project | 3 | 3 | 4 | 2 | 2 | PREREQ |
| 34 | 21 | Open project left drawer | 3 | 3 | 2 | 2 | 5 | PREREQ |
| 34 | 22 | Open project bottom drawer | 3 | 3 | 2 | 2 | 5 | PREREQ |
| 32 | 13 | Navigate to Plugin Manager | 2 | 3 | 4 | 1 | 5 | |
| 32 | 15 | Navigate to Main Help | 2 | 3 | 4 | 1 | 5 | |
| 28 | 7-11 | General / Editor / Build & Run / Terminal / Git preferences | 2 | 3 | 2 | 1 | 5 | |
| 28 | 12 | Navigate to About Cogo | 1 | 3 | 4 | 1 | 5 | |
| 28 | 14 | Navigate to Developer Options | 2 | 3 | 2 | 1 | 5 | |
| -- | 26 | Test FAB drag behaviour | | | | | | not in original list; see item 16 |

**The top three need no UI at all** -- they are `adb shell` reads. Highest value
and lowest cost at once, which is rare enough to act on before anything that
drives a screen.

## Measured reachability

`adb shell` (uid 2000) does **not** hold `START_ANY_ACTIVITY` on this emulator.
Verified by `SecurityException`, not inferred. Only four activities are exported:

- `.activities.SplashActivity`, `.activities.MainActivity`,
  `.activities.CrashHandlerActivity`, `com.termux.app.TermuxActivity`

Everything else -- Preferences, PluginManager, About, Help, FAQ, TerminalActivity,
Editor, Onboarding -- is hard-blocked from `am start -n`.

**There is no deep-link tier.** `PreferencesActivity` never calls `getIntent()`;
no activity accepts a destination extra.

**Keyboard shortcuts are the workaround.** From `MainActivity`: `Ctrl+,`
preferences, `Ctrl+Alt+T` terminal, `Ctrl+N` new project, `Ctrl+O` open project,
`Ctrl+Shift+O` clone. In the editor: `Ctrl+S` save. Note `input keyevent` sends
**no meta state** -- this needs `input keycombination` (API 31+), still unproven
on-device. Items 1, 2, 3 and 6 all depend on it.

## Corrections to the original list

- **Items 1-4 are fragments, not activities.** They are `SCREEN_*` values on
  `MainViewModel`, swapped by view visibility inside `MainActivity`.
- **Item 5 must target `com.termux.app.TermuxActivity`** (exported).
  `.activities.TerminalActivity` is not exported and will fail.
- **Item 19 cannot use an intent.** `EditorActivityKt` is non-exported *and*
  `singleTask` with no `onNewIntent` override, so `PROJECT_PATH` cannot be passed
  externally even in principle.
- **Item 4 is not destructive.** It navigates to the delete screen; it deletes
  nothing.

## Decisions

- **Item 16 is a setup primitive**, not a feature test. It writes
  `shared_prefs/FabPrefs.xml` (`fab_x_ratio`, `fab_y_ratio`) via `run-as`; the
  position is re-applied in `onResume`. Its purpose is parking the FAB away from
  UI under automation -- it is `<include>`d into six layouts and floats over
  content.
  - The nine positions use ratios **0.1 / 0.5 / 0.9**, not 0.0 / 0.5 / 1.0.
    The extremes sit against the `getSafeDraggingBounds()` clamp boundary and
    closer to system gesture zones.
  - This bypasses `DraggableTouchListener` entirely, so the drag itself stays
    untested -- tracked as item 26.

## Known blockers and gotchas

- **`template.json` is not strict JSON.** `{identifier: "APP_NAME"}` has an
  unquoted key; a strict parser rejects it. Lenient parsing required.
- **Templates need a set-up device.** `files/home/.cg/templates/` is empty until
  onboarding completes.
- **Locale fragility.** Preference rows expose a `key`, but a preference key is
  not a view resource-id, so UI automation must match on **title text** -- which
  breaks whenever a translation lands.
- **Anything using `run-as` needs a debuggable build.**

## What we would ask of the CoGo app

Ranked by leverage. All should be **debug-only** -- an exported navigation
surface in a release build lets any installed app drive the IDE.

1. **A debug-only exported navigation entry point** taking a destination key.
   Collapses ~15 items into one mechanism, and the routing tables already exist
   (`MainViewModel.currentScreen`, the preference keys).
2. **Publish the current screen where adb can read it.** One line per screen
   change (`Log.i("CogoNav", "screen=...")`) moves ~10 items from Verify 2 to 5.
   `MainActivity` hosts 7 fragments and `PreferencesActivity` reuses one fragment
   class with a hardcoded toolbar title, so `dumpsys` cannot tell them apart.
3. **Locale-independent identifiers on navigable rows** -- `contentDescription`
   set to the existing preference key.
4. **A `skipAutoOpen` boolean extra on `MainActivity`**, honoured for that launch
   only. Removes `cogo_home`'s need to permanently rewrite a user preference.
5. **`onNewIntent` on `EditorActivityKt`**, debug-exported, honouring
   `PROJECT_PATH`.
6. **An automation mode suppressing the feedback FAB and tooltip overlays**,
   which intercept coordinate taps.

If only two were possible: 1 and 2. Reachability plus verifiability is the whole
problem.
