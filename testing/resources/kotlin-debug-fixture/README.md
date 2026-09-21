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
walks any other. `Greeter.greetAll` carries output lines 60-63, the stdlib `map` body inlined into
it, so Step Over from line 27 walks `27 -> 60 -> 61 -> 62 -> 28` and takes four presses to reach the
lambda body. The `kotlin.*` / `kotlinx.*` step filters do not prevent this, because the inlined body
lives in the user's own class and a class-name filter never sees it. Reachable through any `map`,
`forEach`, `let` or `run`.

The editor stays on a real line throughout. The `KotlinDebug` stratum maps output lines 60-63 back
to `DebugFixture.kt:27`, the `names.map { ... }` call site, and 64-67 back to `DebugFixture.kt:33`,
the `measured("greeter")` call site, so the highlight sits on the call site for each intervening
press instead of moving to line 60 of a 58-line file. Step Over therefore looks like it does nothing
until the press that leaves the inlined body. That is the remaining half of ADFA-4175's inline
stepping item: the position is right, the number of presses is not.

The call stack disagrees with the editor while that happens, and each is right about a different
question. Its rows read the Kotlin stratum, the only stratum with a multi-file table, so the frame
reads `_Collections.kt:1557` - where the code was written - while the editor highlights
`DebugFixture.kt:27` - where the user wrote the call. Breakpoints are still placed and reported in
the Java stratum. Expect the two to differ inside an inlined body and to agree everywhere else.

The variables list carries a stdlib inline function's own locals alongside the user's. Stopped at
line 28, in the lambda passed to `map`, it shows `item$iv$iv` and `destination$iv$iv` - `mapTo`'s
loop variable and accumulator, not yours. The `$iv` suffix is left on deliberately: it is the only
cue separating them from names the user wrote. The same suffix lands on a user's own inline-function
locals, so stepping through `measured` inlined into `Greeter.timed` shows `started$iv`, `result$iv`
and `label$iv`. Stripping it only where it is safe needs the Kotlin stratum.

The receiver of a `run` or `apply` block is hidden along with the compiler's own. It reaches the
frame as `$this$<caller>_u24lambda_u24<n>`, no label a user could have written is recoverable from
that, and ADFA-4191 section 3 asks for `$this$` to be filtered. The object is still reachable under
the name it was called on.

## Steps to QA

For ADFA-4175. `acli` cannot write the `Steps to QA` custom field, so this is the text to paste in.

```gherkin
Given a project made from the Kotlin template with DebugFixture.kt added per Setup
When a breakpoint is set on line 22 and runAll() runs under the debugger
Then execution suspends and the call stack names DebugFixture.kt:22

Given execution is suspended on line 27 of DebugFixture.kt
When the user taps Step Over
Then the editor highlight stays within DebugFixture.kt and never a line past 58
And repeated Step Over reaches line 28

Given execution is suspended on line 22 of DebugFixture.kt
When the user taps Step Over on the string template that null-checks name
Then execution does not stop inside kotlin.jvm.internal.Intrinsics

Given execution is suspended on line 28, inside the lambda passed to map
When the user opens the variables tree
Then no entry whose name starts with $i$, $this$ or $continuation is shown
And the user's own each and greeting are shown

Given execution is suspended on line 41, inside the anonymous Runnable
When the breakpoint on line 41 is set before Greeter loads
Then the breakpoint still binds and hits

Given execution is suspended anywhere in DebugFixture.kt
When the user expands this on a Greeter instance
Then the name field is listed and is not offered as editable
```

A breakpoint on line 7 is expected **not** to hit; see the line map. A breakpoint inside
`suspending` hits, but stepping across the suspension point does not resume - both are known
limitations above, not QA failures.
