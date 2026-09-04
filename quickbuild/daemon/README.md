# `:quickbuild:daemon` - the compile process Quick Build talks to

A plain JVM module, packaged as one runnable jar (`daemonJar`) and staged with its runtime
classpath beside it (`stageDaemon`). CoGo spawns it as a **child process on the bundled JDK** and
speaks line-delimited JSON to its stdin/stdout; it holds the warm state - the Kotlin incremental
caches, the classpath snapshots, the r8 class loader - that makes the second save fast.

Start at [`../README.md`](../README.md) for what Quick Build is and how a save flows through it,
and at [`../protocol/README.md`](../protocol/README.md) for the wire formats. **This file is not a
field reference**: every request, response and option is declared in code and linked below. What
lives here is what the code cannot tell you - why the process is shaped this way, and the traps
that have already cost a debugging session.

## The one rule that shapes everything here: a build error must never end the process

CoGo reads a non-zero exit as daemon death and respawns. If a broken source could kill the daemon,
every save of that file would cost a respawn plus a cold compile, and the user would see a stall
with no diagnostic - the exact failure the warm daemon exists to avoid. So:

- **Tool failures are responses, not throws.** Every op answers `ok:false` with diagnostics;
  `Result.Failed` is the normal outcome of a broken build, not an error path.
- **[`RequestRouter`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/RequestRouter.kt)
  guards the handlers**, converting a throw that escapes one into `ok:false` when it is a failure
  of the *request* rather than of the process (`isRequestFailure`). A fatal internal error - a
  `NoClassDefFoundError`, a broken staging layout - is deliberately **not** caught: that one really
  is daemon death, and hiding it would leave CoGo talking to a process that cannot build.
- **[`DaemonMain.serve`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonMain.kt) has
  its own backstop outside the router**, because the read, the parse and the encode all run on
  request-sized data and none of them is inside a handler.

Exit contract: `shutdown` or stdin EOF exits 0; only a fatal internal error exits non-zero.

## The serve loop: one line in, one line out, single-threaded

- **Stdout carries protocol only.** `DaemonMain` captures the real stdout for responses and
  redirects `System.out` to stderr - the in-process Kotlin compiler prints to stdout, and one stray
  line would corrupt the stream. Progress and warnings go to stderr, which CoGo drains and re-logs;
  **the daemon has no log file of its own.**
- **One request in flight, by contract.** CoGo serializes calls behind a mutex and the loop is
  single-threaded on purpose, which is why the compiler can keep per-compile counters in fields.
- **A malformed line answers `ok:false` and the loop keeps serving**, under the codec's unknown-id
  sentinel when the line never parsed far enough to carry an id.

## The session: built by `configure`, reused by every build op

[`DaemonService`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonService.kt) holds
at most one `Session` - an `IncrementalCompiler`, a `DexTool`, an `Aapt2Link` and the scratch
`outDir`. It is the warm state; the build ops answer `ok:false` if no `configure` ran.

| Stage | What happens | Why it is that way |
| --- | --- | --- |
| validate | every tool path required and non-blank, every classpath entry and plugin existence-checked; a classpath entry may be a jar or a directory | a guessed tool path would compile against another SDK's `android.jar` and fail only on device; a directory entry is real (the Gradle plugin writes the module's own `build/tmp/kotlin-classes/<variant>` into the variant classpath) and is fingerprinted by its contents (below) |
| build the replacement | the new `Session` is constructed **before** the old one is released | construction can throw, and releasing first would leave the still-installed session holding a **closed** r8 class loader - latent damage, since a closed `URLClassLoader` still serves classes it already loaded, so it surfaces later as a `NoClassDefFoundError` from inside d8 |
| swap and release | `session = replacement`, then the previous session's compiler and dex tool are closed | on the in-process compile strategy the engine's project state lives for the **JVM's** lifetime, so a re-configure without this accumulates one project's worth per configure on a 2-4 GB phone |

There is no reconfigure op: a second `configure` replaces the session. `shutdown()` releases the
live session's tools after the loop has stopped serving, and runs on the fatal-rethrow path too.

`configure` also reports `scratchFsType` once per session. It matters more than it looks: rewriting
the same class tree costs ~52x more on Android's FUSE-backed emulated storage than on the app's own
filesystem `[measured on a56, ADFA-4128]`, so a timing row is unreadable without it.

## The two-pass compile: kotlinc first, then javac, into one output tree

[`IncrementalCompiler`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/compile/IncrementalCompiler.kt)
runs the Kotlin Build Tools API's incremental pass, then
[`JavaCompileStep`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/compile/JavaCompileStep.kt)
runs javac over the `.java` sources - both writing into the same `classes` dir.

- **kotlinc is given the `.java` sources too, for resolution only.** A Kotlin file calling a
  same-module Java class will not resolve otherwise, and the `-Xjava-source-roots` flag is silently
  ignored by this entry point. No bytecode is emitted for them; javac does that.
- **The engine tracks no ABI over those Java sources**, so being told "a `.java` changed" tells it
  nothing. [`JavaSourceAbi`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/compile/JavaSourceAbi.kt)
  decides instead: it fingerprints each `.java`'s imports and declarations (bodies excluded), and a
  changed type name forces a **full** Kotlin recompile. An ABI it cannot know - first compile, no
  javac, an unparseable source - is read as "changed", never as "nothing changed".
- **Both compilers pin the same level.** `JVM_TARGET` is shared: kotlinc's `-jvm-target` and
  javac's `--release`. Read `javacOptions`' comment before touching that flag - `--release` pins the
  bytecode level but **not** the platform API surface to the project's `android.jar`.
- **javac deletes nothing.** It rewrites the outputs of the sources it is handed and leaves behind
  the `.class` of a source that was removed, and the `Outer$1.class` of a nested declaration an edit
  dropped. Both are swept explicitly, and an undeletable one **fails the compile** rather than
  letting a stale class reach the dex.
- **The result names the class files this compile touched**, diffed against the last successful
  compile's tree. No deploy ack reaches the daemon, so that tree is only a proxy for what the device
  runs - which is why a client that lost trust after a failed dex or deploy re-declares every source
  changed, and the diff then runs against nothing and reports the whole tree.

### Warm state, and the guard that keeps it honest

The IC caches and the shrunk classpath snapshot survive a re-configure into the same `workDir`, and
that is the point - losing them costs a cold compile. The danger is the opposite case: a standard
Gradle build can rewrite a jar **in place**, same path, new ABI, and a compile that trusts the
surviving snapshot keeps dependents of the changed library stale. That is the worst silent failure
this feature has.

So every classpath entry and every compiler plugin jar is fingerprinted by **path + content**: a
file by its size and CRC, a directory by the sorted relative paths, sizes and CRCs of every file
under it (`File.length()` on a directory is a filesystem constant, so a directory can only be
fingerprinted by walking it). A mismatch (or a missing fingerprint next to surviving state) wipes
both caches. The fingerprint is written **last**, after the per-jar snapshots exist, so a throw
mid-construction cannot leave a fingerprint describing snapshots that were never built.

## dex and relink

- [`DexTool`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/dex/DexTool.kt) drives the
  **device's own** r8 jar reflectively, through a `URLClassLoader` - `<build-tools>/lib/d8.jar` when
  present, a staged jar otherwise. Every reflective step therefore has to fail as a *dex failure*
  with a message naming the toolchain, never as an internal error.
- **Classes are stripped of `ACC_FINAL` first**
  ([`FinalStripper`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/dex/FinalStripper.kt)),
  so the payload matches the gen-0 baseline's opened classes and the generated proxies' `extends`
  stays verifiable. `:gradle-plugin`'s `ClassOpener` does the same job on the build side, and the
  two must stay byte-for-byte identical in scope - a one-sided edit is a verify error on device,
  not a compile error here.
- **More than one dex means the payload split**, which the deploy path cannot use - so the output
  dir is cleared before every run, because the dex count afterwards is the only signal of it.
- [`Aapt2Link`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/res/Aapt2Link.kt) compiles
  the res dirs and links a whole resource **apk** (the wire key is `resourcesArsc` for protocol
  stability). It runs aapt2 as a child process under a watchdog, since a wedged aapt2 would
  otherwise block the single-threaded loop past the client's request timeout and leave the next
  request meeting a still-wedged daemon.

## Traps

- **Never print to stdout.** Use the injected `log` / `warn` channels, which reach stderr.
- **`kotlin-daemon-client` and `kotlin-daemon-embeddable` look like dead weight and are not.**
  Excluding them throws `NoClassDefFoundError` from inside the in-process path. `build.gradle.kts`
  records which exclusion is safe and why.
- **A `Result.Failed` diagnostic list is bounded.** kotlinc emits one unresolved-reference error per
  use site, so a deleted dependency yields hundreds; the whole list rides one protocol line into a
  phone-screen panel. All three tool paths cap, each with a "+K more ... elided" marker.
- **The test suite compiles for real** - real BTA service, real kotlinc, real IC caches - so an
  engine that silently falls back to a full compile goes red rather than green-and-slow. The aapt2
  and d8 cases are gated on the device toolchain being present; set `REQUIRE_BUILD_TOOLCHAIN=1` to
  fail instead of skip when it is absent.

## Key files

| File | Role |
| --- | --- |
| [`DaemonMain.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonMain.kt) | process wiring, the stdout/stderr split, the serve loop and its backstop |
| [`DaemonService.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonService.kt) | the op implementations; owns the session and its lifecycle |
| [`protocol/RequestRouter.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/RequestRouter.kt) | dispatch plus the request-versus-process failure split |
| [`protocol/ProtocolCodec.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/ProtocolCodec.kt) | parse and encode one line |
| [`compile/IncrementalCompiler.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/compile/IncrementalCompiler.kt) | the incremental Kotlin pass, the classpath fingerprint, the output diff |
| [`compile/JavaCompileStep.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/compile/JavaCompileStep.kt) | the javac pass and its options |
| [`compile/JavaSourceAbi.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/compile/JavaSourceAbi.kt) | what a Java edit costs the Kotlin side |
| [`dex/DexTool.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/dex/DexTool.kt) | reflective d8 against the device's r8 jar |
| [`dex/FinalStripper.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/dex/FinalStripper.kt) | `ACC_FINAL` removal, mirrored by `:gradle-plugin`'s `ClassOpener` |
| [`res/Aapt2Link.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/res/Aapt2Link.kt) | aapt2 compile + link, the argfile, the watchdog |
| [`build.gradle.kts`](build.gradle.kts) | the runnable-jar and staging layout, and the dependency notes |
