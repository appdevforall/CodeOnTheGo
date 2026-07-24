# Running annotation processors in the quick-build daemon

What it would take for the on-device daemon to run KSP/kapt itself, instead of standing
down to a full Gradle build whenever an edit touches processor input.

Scope note: this is the *research* half of ADFA-4128 WS-E. The shipped half is
annotation-aware classification (`quick-build/src/main/java/.../domain/annotations/`),
which keeps a processor-using project on the fast path for edits that provably miss
processor input. That change removes most of the pain; running processors in the daemon
would remove the rest. Nothing in this document is implemented.

All evidence below was gathered by inspecting the artifacts resolved in this workspace's
Gradle cache. Versions are named per claim; anything unverified says so.

## Where the cost is

| | before WS-E | after WS-E track 1 | with processors in the daemon |
|---|---|---|---|
| edit that misses processor input | ~8 s Gradle rebaseline | fast path (~1 s) | fast path |
| edit that touches processor input | ~8 s Gradle rebaseline | ~8 s Gradle rebaseline | fast path + processor run |

So the remaining prize is narrow: DAO/entity/module edits only. That should temper how much
machinery is worth spending here.

## Matrix

| Mechanism | Verdict | Cost to wire | Evidence |
|---|---|---|---|
| Java `annotationProcessor` (JSR 269) in the existing javac step | **cheap** | drop `-proc:none`, add `-processorpath` | `JavaCompileStep` already runs `ToolProvider.getSystemJavaCompiler()` in-process on-device and passes `-proc:none` explicitly |
| kapt via the daemon's existing `-Xplugin=` route | **possible** | a 475 KB jar + ~6 plugin options + a stub/apt round | `kotlin-annotation-processing-gradle-2.3.0.jar` is a plain kotlinc plugin (`META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`); the daemon already passes `-Xplugin` jars for Compose |
| KSP2 standalone (`KotlinSymbolProcessing` + `KSPJvmConfig`) | **possible, heavy** | an 83 MB embeddable jar, a second analysis session per build | `symbol-processing-aa-embeddable-2.3.6.jar` (83,097,759 bytes) ships `com/google/devtools/ksp/impl/KotlinSymbolProcessing.class` and `com/google/devtools/ksp/cmdline/KSPJvmMain.class` |
| KSP1 as a kotlinc compiler plugin (would ride the cheap route) | **gone** | n/a | KSP 2.3.6 publishes only `symbol-processing-api`, `-aa-embeddable`, `-common-deps`, `-gradle-plugin`; there is no `symbol-processing-cmdline` |
| Room's query verifier on-device | **walled today, and the wall looks removable** | see below | `DatabaseVerifier$Companion.create` catches `java.lang.Exception` only |

## Java annotation processors — the cheap one

`quickbuild-daemon/.../compile/JavaCompileStep.kt` already compiles the project's `.java`
sources in-process with the JDK's javac, and deliberately disables processing:

```
"-proc:none",   // "Annotation processing is a full-Gradle-build concern (plan Q4)"
```

Everything a JSR-269 processor needs is therefore already running on the device. Enabling it
is `-processorpath <the ksp/kapt/annotationProcessor configuration>` plus routing the
generated sources back into the compile. That only covers **Java** processors on **Java**
sources — no Kotlin-declared `@Entity` — so on its own it does not help a Kotlin-first Room
app. It is listed because it is nearly free and would cover Java-only projects (`hello-java`
and kin in the corpus).

## kapt

kapt is a kotlinc compiler plugin, so it rides the exact mechanism the daemon already uses
for the Compose compiler plugin (`IncrementalCompiler.pluginArguments`, one `-Xplugin=` per
jar). Requirements, in order of risk:

1. **The plugin jar.** `kotlin-annotation-processing-gradle-2.3.0.jar` is 475 KB and
   registers `KaptComponentRegistrar` / `KaptCommandLineProcessor` through
   `META-INF/services`. Small enough to bundle without thinking about it.
2. **javac.** kapt generates Java stubs and then runs javac with the processors on the
   processor path. The daemon already has a working in-process javac (above), so this is
   satisfied rather than an open question.
3. **Plugin options.** kapt needs `apclasspath`, `sources`, `classes`, `stubs`,
   `incrementalData`, `aptMode` — all passable as `plugin:org.jetbrains.kotlin.kapt3:<key>=<value>`
   through the same free-form argument list.
4. **Cost.** kapt's stub round is a second full pass over the module's Kotlin. It is the
   slowest of the three mechanisms per edit, and it is deprecated upstream in favour of KSP2.

Verdict: mechanically the *easiest* to try, because the daemon's compiler-plugin plumbing
already exists. Worth a spike precisely because a negative result is cheap.

## KSP2 standalone

KSP 2.x dropped the compiler-plugin (KSP1) implementation; the surviving entry point is a
standalone, pure-JVM one:

- `com.google.devtools.ksp.impl.KotlinSymbolProcessing(config: KSPConfig, providers: List<SymbolProcessorProvider>, logger: KSPLogger)` — constructor descriptor confirmed in
  `symbol-processing-aa-embeddable-2.3.6.jar`. There is also a CLI entry point
  (`com.google.devtools.ksp.cmdline.KSPJvmMain`) if we would rather fork a process.
- The config is `KSPJvmConfig` (source in `symbol-processing-common-deps-2.3.6-sources.jar`,
  `com/google/devtools/ksp/KSPConfig.kt`). Its shape maps cleanly onto what a daemon session
  already holds:

  | `KSPJvmConfig` field | daemon already has |
  |---|---|
  | `sourceRoots`, `javaSourceRoots` | the layout's source roots |
  | `libraries` | the session classpath from `setup.json` |
  | `jdkHome`, `jvmTarget` | the bundled JDK, target 17 |
  | `kotlinOutputDir`, `javaOutputDir`, `classOutputDir` | would be new dirs under the daemon work dir |
  | `cachesDir` | ditto |
  | `incremental`, `modifiedSources`, `removedSources`, `changedClasses` | the coalesced changed-set |

**Incremental is first-class**, which is the important part: the config takes the modified /
removed / changed-class sets the orchestrator already computes, so a KSP run per edit would
not have to reprocess the world.

Two real costs:

- **83 MB.** `symbol-processing-aa-embeddable` bundles a shaded IntelliJ + Kotlin Analysis API
  (classes appear under a `ksp/` shade prefix). That is a large addition to CoGo's assets, on a
  product whose whole point is low-end offline devices. Sharing the Analysis API with the
  Kotlin Build Tools API implementation the daemon already loads is not something the
  embeddable jar is built for.
- **A second analysis session.** KSP2 builds its own Analysis API session over the sources;
  it does not reuse the BTA compile. Per-edit latency would be roughly "KSP session + compile",
  not "compile plus a bit". Unmeasured.

## Room specifically

The brief asked three questions. Answers, with evidence, against **Room 2.8.4**:

### (a) Does `room.verifySchema=false` / a schema-location option sidestep the verifier?

**No — that option does not exist.** The complete set of processor options in
`androidx/room/processor/Context$BooleanProcessorOptions` and `Context$ProcessorOptions`
(room-compiler 2.8.4) is:

```
room.expandProjection  room.exportSchemaResource  room.generateKotlin
room.incremental       room.useNullAwareTypeAnalysis
room.schemaLocation    room.internal.schemaInput  room.internal.schemaOutput
```

There is no verification switch. The only opt-out is the source-level
`@SkipQueryVerification` annotation (referenced once from `DatabaseProcessor`), which is the
user's code to write, not something the daemon may impose.

### (b) Does a current sqlite-jdbc ship a Linux-Android aarch64 native?

**Yes — and this is the interesting finding.** `sqlite-jdbc-3.41.2.2.jar` (the version in this
workspace's cache) ships:

```
org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so
```

and it is a genuine bionic build. Its `DT_NEEDED` entries, read straight out of the ELF:

| variant | NEEDED |
|---|---|
| `Linux-Android/aarch64` | `libm.so`, `libc.so`, `libandroid.so`, `libdl.so`, `liblog.so` |
| `Linux/aarch64` | `libm.so.6`, `libpthread.so.0`, `libc.so.6` |

The `libm.so.6` failure recorded in the prior on-device work is exactly the **`Linux/aarch64`**
variant — i.e. the wall was *the wrong native being selected*, not the absence of an Android
native. Selection is `org.sqlite.util.OSInfo.isAndroid()`, which is
`isAndroidRuntime() || isAndroidTermux()`:

- `isAndroidRuntime()` tests `java.runtime.name` for "android". CoGo's daemon runs a **bundled
  OpenJDK**, whose `java.runtime.name` is "OpenJDK Runtime Environment" — so this returns false.
- `isAndroidTermux()` shells `uname -o` (the string is in `OSInfo`, alongside `uname -m`,
  `/etc/os-release`, `/proc/self/map_files`). Whether CoGo's bundled Termux `uname` answers
  "Android" is **unverified** — it needs a device, which WS-E did not own.

Either way there is an explicit escape hatch: `SQLiteJDBCLoader` reads
**`org.sqlite.lib.path`** and **`org.sqlite.lib.name`** (both string constants in
`SQLiteJDBCLoader.class`), so the daemon can extract the Linux-Android `.so` once and point
those system properties straight at it, with no patching of Room or sqlite-jdbc.

One more requirement from `DatabaseVerifier$Companion`: it insists on a temp dir that is
"readable, writable and allow executables", via `java.io.tmpdir` or **`org.sqlite.tmpdir`**.
Also settable.

### (c) Does the KSP (not kapt) path avoid the verifier?

**No.** `room-compiler-2.8.4.jar` is a single artifact serving both backends —
`META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` names
`androidx.room.RoomKspProcessor$Provider` and
`META-INF/services/javax.annotation.processing.Processor` names `androidx.room.RoomProcessor`.
Verification lives in the shared `DatabaseProcessor` (14 references to `DatabaseVerifier`), so
the backend makes no difference.

### Why the verifier is a *hard* failure rather than a warning

Room is written to degrade gracefully — `DatabaseVerificationErrors` carries

> Room cannot create an SQLite connection to verify the queries. Query verification will be
> disabled. Error: %s

but the guard that reaches it is too narrow. The exception table of
`DatabaseVerifier$Companion.create` (parsed from the class file) is:

```
create  -> [java/lang/Exception]
cleanup -> [java/sql/SQLException]
```

A failed `System.load` throws `java.lang.UnsatisfiedLinkError`, which is an `Error`, **not** an
`Exception` — so it escapes `create`'s catch and kills the processing round instead of
downgrading to that warning. That single detail explains why the prior on-device attempt
failed hard, and it means there are two independent fixes: make the native load succeed
(above), or get the load failure surfaced as an `Exception`.

**Net: the Room wall is very likely removable without touching Room**, by extracting
`Linux-Android/aarch64/libsqlitejdbc.so` and setting `org.sqlite.lib.path` /
`org.sqlite.lib.name` / `org.sqlite.tmpdir` before the processor runs. That is a hypothesis
with strong static evidence and **no on-device confirmation yet**.

## Recommended next step

One cheap experiment, on the A56, before any of the machinery above:

> Run a host-side JVM program on the device (the E8/E9 `run-as` mechanism) that opens
> `jdbc:sqlite::memory:` through xerial sqlite-jdbc with `org.sqlite.lib.path` pointed at an
> extracted `Linux-Android/aarch64/libsqlitejdbc.so`, and print `OSInfo.getOSName()`.

It is maybe an hour, and it settles the one question everything else hangs on. If the
connection opens, Room's verifier is not a wall and the mechanism choice becomes a plain
cost comparison: **kapt first** (475 KB, rides the existing `-Xplugin` route, negative result
is cheap) and KSP2 only if kapt's stub round proves too slow — with the 83 MB asset cost
argued explicitly, since it lands on the low-end offline devices the product exists for.

If the connection does not open, the fallback is unchanged and honest: processor-touching
edits keep going to Gradle, which after WS-E track 1 is a small minority of edits.
