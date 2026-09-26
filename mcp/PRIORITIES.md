# cogo-mcp tool priorities

Supersedes `TODO.txt`. Scored 2026-08-11 against a real emulator, not from
reading the source alone -- several entries changed once measured.

## Status

**Built:** `ping`, `is_cogo_installed`, `cogo_home` (items 6-adjacent), plus the
top three -- `list_projects` (18), `list_templates` (17), `list_project_files`
(23). 69 tests.

**Blocked on defects.** A three-lens code review found problems serious enough
that they outrank every remaining tool below. See "Review defects". Adding tools
before fixing them multiplies the same mistakes across a wider surface: the
injection and quoting faults are in the shared command-building idiom, and the
absent CI means nothing catches a regression on merge.

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

## Review defects

Found 2026-08-11 by three independent reviews (correctness, silent-failure,
test-quality). Every item was reproduced on `emulator-5554` or by mutating the
source and re-running the suite -- none is speculative. Fix these before adding
tools: the faults are in the shared command-building idiom, so each new tool
copies them.

| Sev | Defect | Where |
|---|---|---|
| Critical | **Command injection.** `runAs` wraps its payload in double quotes, and `readMemberCommand` interpolates the archive name and member path -- both device-derived -- unquoted. A crafted `.cgt` executed an arbitrary command on the device during review. Plugins can add archives. | `Templates.kt:62-65`, `CogoDevice.kt:15` |
| Critical | **`cogo_home` can overwrite every preference.** `cat ... \|\| true` masks a *failed* read as an empty one, and `withAutoOpenDisabled("")` then returns a fresh 3-line document that replaces theme, locale, SDK paths and all. Reported as success. Survives mutation testing: no test catches it, because every fake returns an empty prefs read. | `CogoHome.kt:61-67`, `CogoDevice.kt:19` |
| Critical | **`list_project_files` reports a project that is not open.** `ide_last_project` is "most recently opened, ever" -- production never writes the sentinel back. After `cogo_home`, the tool confidently describes a project while the IDE sits on its home screen. | `ProjectFiles.kt:34-38` |
| High | **`SystemAdb.run` throws instead of returning `AdbResult`** when `adb` is not on PATH, bypassing `adbFailure` entirely. | `Adb.kt:37` |
| High | **No timeout anywhere.** A wedged adb hangs the tool, the request and the agent indefinitely. `cogo_home` makes up to 33 such calls. | `Adb.kt:37-53` |
| High | **Unreadable projects directory reads as "No projects".** `cd "$dir" \|\| exit 0` cannot be rescued by any exit code; at mode 111 the glob simply fails to expand and stderr is discarded. | `Projects.kt:38-39` |
| High | **The stderr drain thread is untested.** Removing it passes all 69 tests; 256KB of stderr deadlocks permanently. The test writes 5 bytes -- below any pipe buffer. | `Adb.kt:40-47` |
| Medium | A stale recorded project path reports "adb failed" when adb worked perfectly. | `ProjectFiles.kt:111` |
| Medium | An unreadable templates directory reports "not installed yet" *and volunteers onboarding as the cause*. | `Templates.kt:16,68` |
| Medium | `cogo_home` writes the whole prefs document through argv; measured to break above ~16KB, permanently. | `CogoHome.kt:46-49` |
| Medium | `resumedActivity` cannot tell "wrong activity" from "could not parse dumpsys", and reports the second as the first. | `CogoHome.kt:98` |
| Medium | **Nothing runs these tests in CI.** `mcp/` is absent from the root build and from every workflow, so all 69 tests run only by hand. Every gap here is unguarded on merge. | -- |
| Low | No test asserts any tool `description`, though `ServerDescriptionTest`'s own header and the README both claim it does. | `ServerDescriptionTest.kt` |
| Low | Nothing pins `host = "127.0.0.1"`, though the README makes a security claim about it. A one-character edit exposes the server to the LAN. | `Main.kt:13` |
| Low | The "adb shell emits CRLF" comments are false for this adb (measured LF), and the `trim()` calls they justify are dead -- `lineSequence()` already splits CRLF. Meanwhile `withAutoOpenDisabled`, the one parser that does raw string surgery, has **no** CRLF test and grows unboundedly on CRLF input. | five sites |

### The pattern worth remembering

`exitCode` is checked at all 12 call sites. It is nonetheless the wrong signal in
four of them, because each of those commands was deliberately written to keep the
*outer* exit status at zero (`|| true`, `|| exit 0`, `unzip -p`). The check is
real and inspects a value the command guarantees. Guard the inner failure, or
emit an explicit marker the parser can recognise -- as `listProjectsCommand`
already does with `NO_PROJECTS_DIR`.

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
7. **Record which project is *currently open*, distinct from the last one ever
   opened.** This one is not a wish -- it is a defect we inherited.
   `ide_last_project` is only ever written on open (`MainActivity.kt:407`) and
   never cleared, so after `cogo_home` the IDE sits on its home screen while the
   preference still names a project. There is no on-device signal for "nothing is
   open", so `list_project_files` cannot answer honestly no matter how it is
   written. Clearing the key on close, or publishing the open project alongside
   ask 2, fixes it at the source.

If only two were possible: 1 and 2. Reachability plus verifiability is the whole
problem. Ask 7 is the cheapest of the lot and removes a wrong answer rather than
an inconvenience.
