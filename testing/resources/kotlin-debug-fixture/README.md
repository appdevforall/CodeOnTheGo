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
it from the app's own scope if the template provides one.

## Line map

Each line below is a breakpoint target. Line numbers are stable as long as the file is not edited.

| Line | Construct | Compiles to |
|------|-----------|-------------|
| 7 | inline function body | the **caller's** class, not `measured` |
| 14 | top-level function | `DebugFixtureKt` |
| 22 | class method | `Greeter` |
| 28 | lambda passed to `map` | the caller's class (`map` is inline) |
| 34 | lambda passed to an inline function | the caller's class |
| 41 | anonymous object | `Greeter$deferred$1` - a separate class |
| 48 | suspend function | `DebugFixtureKt` + a continuation |

Line 41 is the important one. A Kotlin file compiles to many classes, and `Greeter` loads before
`Greeter$deferred$1`, so a breakpoint there only binds if the `ClassPrepare` request is not capped
at the first matching class (ADFA-4190).

Line 34 is the inline case: its Java-stratum line number is synthetic and past the end of the file,
so the debugger must report the Kotlin-stratum position instead (ADFA-4190, ADFA-4191).

## Known limitation

Stepping across a coroutine suspension point does not work. A suspend function resumes on a
different thread and JDI's step request is thread-scoped, so the step is lost. Breakpoints inside
suspend bodies still hit. See ADFA-4175.
