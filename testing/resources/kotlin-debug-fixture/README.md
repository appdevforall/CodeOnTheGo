# Kotlin debugger fixture

`DebugFixture.kt` is the debuggee for the Kotlin debugger work (ADFA-4175). It is a plain source
file rather than a whole project: CoGo's own Kotlin template already produces a working Android
project, so QA exercises the same path a user does instead of a bespoke fixture that can drift.

## Setup

1. In CoGo, create a new project from a **Kotlin** template.
2. Copy `DebugFixture.kt` into `app/src/main/java/com/example/debugfixture/`.
3. Call `runAll()` from `MainActivity.onCreate`, after `setContentView`.
4. Build and launch under the debugger (the bug icon, not the play icon).

`runAll` reaches every construct below except `suspending`, which needs a coroutine context - call
it from the app's own scope if the template provides one. It reaches `measured` only inlined into
its callers, never as a call into `measured` itself, so the line 7 breakpoint is expected not to
hit; see the line map.

## Line map

Each line below is a breakpoint target. Line numbers are stable as long as the file is not edited.

| Line | Construct | Compiles to |
|------|-----------|-------------|
| 7 | inline function body | `DebugFixtureKt` - does **not** hit, see below |
| 14 | top-level function | `DebugFixtureKt` |
| 22 | class method | `Greeter` |
| 28 | lambda passed to `map` | the caller's class (`map` is inline) |
| 34 | lambda passed to an inline function | the caller's class |
| 41 | anonymous object | `Greeter$deferred$1` - a separate class |
| 48 | suspend function | `DebugFixtureKt` + a continuation |

Line 41 is the important one. A Kotlin file compiles to many classes, and `Greeter` loads before
`Greeter$deferred$1`, so a breakpoint there only binds if the `ClassPrepare` request is not capped
at the first matching class (ADFA-4190).

Line 34 is the inline case. The debugger places and reports every location in the Java stratum, and
the Kotlin inliner writes the inlined body's lines into the caller's line table under synthetic
numbers past the end of the file. `javap -l` on the compiled fixture shows `DebugFixtureKt.measured`
keeping its real lines 7-10, and `Greeter.timed` carrying the inlined copy as lines 64-67 of a
58-line file.

So a breakpoint on line 7 binds against `measured`'s own class and never against the copy inlined
into a caller. Since `runAll` only ever reaches `measured` inlined, that breakpoint does not hit at
all - expected, not a defect to file. Reporting the Kotlin-stratum position is what would fix it.

## Known limitations

Stepping across a coroutine suspension point does not work. A suspend function resumes on a
different thread and JDI's step request is thread-scoped, so the step is lost. Breakpoints inside
suspend bodies still hit. See ADFA-4175.

Step Over does not treat an inlined body as one step: it walks the inlined lines the same way it
walks any other. Skipping them needs the Kotlin stratum to tell a library body inlined into the
user's class apart from a lambda the user wrote there, which the inline markers alone cannot do.

While it walks them, the editor is sent the **wrong file**, not only a line past the end of one.
`Greeter.greetAll` carries output lines 60-63, which the SMAP maps to `_Collections.kt:1557` and
`1628-1630`, the stdlib `map` body. The Java stratum has one `SourceFile` per class, so those lines
are reported as `DebugFixture.kt`: Step Over in `greetAll` walks `27 -> 60 -> 61 -> 62 -> 28` and
asks the editor to highlight `DebugFixture.kt:60` in a 59-line file while execution is really in the
standard library. The `kotlin.*` / `kotlinx.*` step filters do not prevent this, because the inlined
body lives in the user's own class and a class-name filter never sees it. Reachable through any
`map`, `forEach`, `let` or `run`.
