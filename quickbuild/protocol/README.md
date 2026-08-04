# Quick Build wire formats (`:quickbuild:protocol`)

Quick Build runs in three processes: CoGo (the IDE), the compile daemon (a JVM child process),
and the runtime living inside the user's proxy app. This module holds the wire types the first
two share, and this file is the reference for all three formats they speak - so a change
described here is a change to three processes at once, and those three are **not** upgraded
together.

Start at [`../core/README.md`](../core/README.md) for what Quick Build is and how a save flows
through it. This file assumes that vocabulary (proxy app, payload, generation, baseline).

## Three formats, and only one of them lives in this module

| Format | Transport | Written by | Read by |
| --- | --- | --- | --- |
| Daemon protocol | line-delimited JSON over the daemon's stdin/stdout | CoGo [`DaemonProcessClient`](../core/src/main/java/org/appdevforall/cotg/quickbuild/data/DaemonProcessClient.kt) (requests), daemon [`ProtocolCodec`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/ProtocolCodec.kt) (responses) | the other end of the same pair |
| Deploy metadata | the `metadataJson` string argument of `IQuickBuildTarget.onPayload` (binder) | [`PayloadDeployer.metadata`](../core/src/main/java/org/appdevforall/cotg/quickbuild/service/PayloadDeployer.kt) | runtime [`DeployMetadata.java`](../runtime/src/main/java/com/itsaky/androidide/quickbuild/runtime/DeployMetadata.java) |
| Build status | the `statusJson` string argument of `IQuickBuildTarget.onBuildStatus` (binder) | [`BuildStatusJson.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/service/BuildStatusJson.kt) | runtime [`BuildStatus.java`](../runtime/src/main/java/com/itsaky/androidide/quickbuild/runtime/BuildStatus.java) |

Only the daemon protocol has shared types
([`DaemonProtocol.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/DaemonProtocol.kt)),
because both its ends compile against this module - `:quickbuild:daemon` takes it as `api`,
`:quickbuild:core` as `implementation`.

`:quickbuild:runtime` does **not** depend on this module at compile scope (only on its test
fixtures, which is where the shared offline-guard scanner lives). So the two binder payload
shapes are held by an encoder/parser **pair** and nothing else: adding a field means editing both
files, and no compiler will tell you if you edit one.

The package here is `...daemon.protocol` though the module is `:quickbuild:protocol` - easy to
mis-navigate. `:quickbuild:daemon`'s own `protocol/` package holds only the codec and the
dispatcher.

Two other JSON formats are out of scope for this file, because they are build-time files rather
than messages between running processes: `assets/quickbuild/components.json` and `setup.json`,
both written by `:gradle-plugin`.

## Daemon protocol: one JSON object per line, one reply per request

### Transport rules

- One request object per stdin line; one response line per request, flushed immediately.
- Blank lines are skipped. Gson escapes newlines inside strings, so a response is always
  exactly one line.
- **Stdout carries protocol only.** [`DaemonMain`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonMain.kt)
  captures the real stdout for responses and redirects `System.out` to stderr, because the
  in-process Kotlin compiler prints to stdout and one stray line would corrupt the stream.
- The daemon has no log file. Progress goes to stderr, which CoGo drains and re-logs; how to
  read it, and how to run the daemon standalone, are in
  [debugging.md](../core/docs/debugging.md#the-daemon-has-no-log-of-its-own).
- **One request in flight.** CoGo serializes every call behind a mutex; the daemon's loop is
  single-threaded on purpose.
- `id` is a caller-assigned integer, echoed on the matching response. Uniqueness is convention;
  the daemon does not enforce it.
- A malformed line answers `ok:false` with one ERROR diagnostic and the loop keeps serving. When
  the id could not be read it answers with `-1`.
- **Exit contract:** build errors never exit. `shutdown` or stdin EOF exits 0. Only a fatal
  internal error exits non-zero, which CoGo reads as daemon death and respawns from.

### Requests

`{"id": <int>, "op": "<name>", ...}`. A required field that is missing or of the wrong type is a
parse failure answered with `ok:false`, never a crash.

**`configure`** - opens the session and fixes everything constant for it. A repeat `configure`
replaces the session state; there is no separate reconfigure op. Every path is absolute.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `projectRoot` | string | yes | identifies the session; never written to |
| `classpath` | string[] | yes | compile classpath jars, snapshotted once here |
| `outDir` | string | yes | daemon-owned scratch: classes, dex, IC caches, aapt2 output. Created if absent |
| `aapt2` | string | no | discovered at `$ANDROID_HOME/build-tools/<newest>/aapt2` |
| `d8Jar` | string | no | discovered at `$ANDROID_HOME/build-tools/<newest>/lib/d8.jar` |
| `androidJar` | string | no | discovered at `$ANDROID_HOME/platforms/android-<highest>/android.jar` |
| `minApi` | number | no | default `30`, the Quick Build floor |
| `compilerPlugins` | string[] | no | jars passed as `-Xplugin=` on every compile of the session |

- Toolchain discovery exists for **external** callers, not for CoGo. CoGo's client always sends
  `aapt2`, `d8Jar` and `androidJar`, and never sends `minApi`. The benchmark harness is the
  caller that omits them.
- A field the caller does send is used as-is: no discovery, no fallback.
- If a field is omitted and `ANDROID_HOME` is unset, or the SDK lacks the tool, `configure` fails
  with one diagnostic per unresolved field naming which one and why
  ([`ToolchainDiscovery`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/ToolchainDiscovery.kt)).
- Every classpath entry, compiler plugin and resolved tool is existence-checked here, so a
  missing file fails at session start rather than mid-build.

**`compile`**, **`dex`**, **`relink`** - the build ops. All three answer `ok:false` if no
`configure` ran first.

| Op | Field | Type | Required | Notes |
| --- | --- | --- | --- | --- |
| `compile` | `allSources` | string[] | yes | every module source, not just the edited ones |
| `compile` | `changedFiles` | string[] | yes | sources edited since the last build. CoGo repeats all sources here on a session's first compile, to seed the incremental caches |
| `compile` | `removedFiles` | string[] | no | omitted when empty |
| `dex` | `classesDirs` | string[] | yes | class-tree roots merged into one dex, in the order given; a later root wins a path collision |
| `relink` | `resDirs` | string[] | yes | the project's own `res/` roots |
| `relink` | `manifest` | string | yes | the merged AndroidManifest.xml to link against |
| `relink` | `stableIds` | string | no | AGP's `stableIds.txt` from the proxy app build. Omitting it relinks with aapt2's own unpinned ids |
| `relink` | `libraryResources` | string[] | no | pre-compiled `.flat` units passed as `-R` overlays. Omitting it relinks against the project's `res/` alone |

**`ping`** and **`shutdown`** carry no fields beyond `id` and `op`, and are answered by the
router without reaching a handler.

### Responses

`{"id": <int>, "ok": true|false, <op values, flat>, "diagnostics": [...]}`

- `values` are flat JSON scalars, never nested, so a client can read one key and ignore the
  rest. A collection value encodes as an array of strings.
- `diagnostics` is present only when non-empty, and **can appear on success** - a build can
  succeed with warnings.
- `ok:false` implies at least one ERROR diagnostic.

Diagnostic entry:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `severity` | string | yes | `ERROR` or `WARNING`. CoGo reads anything that is not `WARNING` as an error |
| `message` | string | yes | the tool's text, verbatim, possibly multi-line. CoGo substitutes `"unknown error"` if absent, rather than dropping the diagnostic |
| `file` | string | no | absent when the tool reported no location (e.g. a bad aapt2 argument) |
| `line` | number | no | 1-based |
| `column` | number | no | 1-based |

`line` and `column` are JSON **numbers** here. The build-status format below carries the same
two as **strings** - the two formats have different parsers and do not share this convention.

### Response values, by op

| Op | Key | Type | Meaning |
| --- | --- | --- | --- |
| `configure` | `durationMillis` | number | time to build the session's snapshots |
| `configure` | `protocolVersion` | number | see below |
| `configure` | `scratchFsType` | string | filesystem type of `outDir` |
| `compile` | `classesDir` | string | the compiled class tree |
| `compile` | `durationMillis` | number | whole-op wall time |
| `compile` | `kotlinMillis` | number | kotlinc span |
| `compile` | `javaMillis` | number | javac span |
| `compile` | `classesChanged` | string[] | `.class` paths this run emitted, relative to `classesDir` |
| `dex` | `dexFile` | string | the single merged `classes.dex` |
| `dex` | `durationMillis` | number | whole-op wall time |
| `dex` | `stripMillis` | number | the `final`-stripping pass |
| `dex` | `d8Millis` | number | d8 itself |
| `relink` | `resourcesArsc` | string | **the full relinked resource apk**, not a bare table. The key keeps its old name for protocol stability |
| `relink` | `durationMillis` | number | whole-op wall time |
| `relink` | `aapt2CompileMillis` | number | aapt2 compile span |
| `relink` | `aapt2LinkMillis` | number | aapt2 link span |
| `ping` | `protocolVersion` | number | the only value `ping` returns |
| `shutdown` | - | - | `ok` only |

Two absent-key behaviours on the CoGo side are deliberate and worth knowing before you delete a
key:

- An absent `classesDir` / `dexFile` / `resourcesArsc` is **not** a hard failure. The client
  falls back to a conventional path under the configured `outDir`, so a field-name mismatch
  degrades instead of breaking.
- An absent `classesChanged` reads as `null`, meaning *unknown*, which the deploy policy handles
  conservatively. An empty array means *nothing changed*. The two are not interchangeable.

### Per-build statistics: what the op did, not just how long two compilers took

`kotlinMillis` and `javaMillis` account for roughly half a warm edit `[measured on a56]`. The
rest is the output-tree snapshots, the Java-ABI re-parse and the per-file I/O around them. Two
readings that gap produced, and that these fields exist to prevent:

- javac read as "the bottleneck" when javac is 19-27% of a warm edit `[measured on a56]`.
- A 53 s first build read as a per-edit cost, when it was the cold compile seeding the caches.

Every field is a counter or a duration. Nothing is derived from a path, a name or source
content, so the whole set is safe to forward to analytics.

| Key | Type | Op | Meaning |
| --- | --- | --- | --- |
| `preSnapMillis` | number | `compile` | walking the output tree before the compile, to diff against |
| `postSnapMillis` | number | `compile` | the same walk after, which yields the changed-class set |
| `javaAbiSnapMillis` | number | `compile` | re-parsing every `.java` source's declarations to decide whether a Java ABI moved |
| `nAllSources` | number | `compile` | size of the source set handed to the compiler |
| `nKotlinToCompile` | number | `compile` | Kotlin sources actually recompiled - the number that explains a slow row |
| `nJavaSources` | number | `compile` | `.java` sources, all of which javac recompiles every build |
| `nChangedClasses` | number | `compile` | `.class` files this build emitted or rewrote |
| `compileOrdinal` | number | `compile` | 1-based compile index within the session |
| `nClassFiles` | number | `dex` | `.class` files read, stripped and dexed |
| `classBytes` | number | `dex` | their total size in bytes |
| `scratchFsType` | string | `configure` | filesystem type of `outDir` (`ext4`, `f2fs`, `fuse`, ...); `unknown` if unreadable |

Two of these are context rather than cost, and a timing row is hard to read without both:

- **`compileOrdinal`** - `1` is the cold build: it seeds the incremental caches and pays
  kotlinc's warm-up. A fresh `configure`, including a respawn, restarts the count, which is
  correct - a respawn re-pays that cost.
- **`scratchFsType`** - rewriting the same class tree costs about 52x more on Android's
  FUSE-backed emulated storage than on the app's own filesystem `[measured on a56]`. A duration
  cannot be compared across devices or configurations without it.

**Absent versus zero.** `CompileStats.fromValues` / `DexStats.fromValues` return `null` when
**none** of their group's keys are present, so a daemon predating the group never yields a
zero-filled row that reads as "measured, and it was free". Within a group the signal is weaker:
`toValues()` emits every field including zeros, so a single missing key defaults to `0` and a
measured zero is indistinguishable from an unmeasured one.

### `protocolVersion` is a session gate, and adding a key must not bump it

`DaemonResponse.PROTOCOL_VERSION` is currently `1`. It is stamped into `ping` success and
`configure` success.

- CoGo aborts the session when `configure`'s `protocolVersion` **differs or is absent** - absence
  fails too, because the daemon has stamped it since the protocol existed, so a missing field
  means "not our daemon". The check lives in `DaemonProcessClient.start`.
- **Adding a response key is additive and must NOT bump the version.** A staged daemon jar can
  lag the client that talks to it; bumping the version for a new optional key would break
  exactly the pairing the additive shape exists to support. An older daemon omits the key; a
  newer one adds one the older client ignores.

### Adding a numeric stat touches five places, and the codec is not one of them

`ProtocolCodec.encode` is generic over the response's values, so it needs no change. What does:

1. The property on `CompileStats` or `DexStats`.
2. Its `KEY_*` constant.
3. Its `toValues()` entry.
4. Its `fromValues()` read.
5. The private `KEYS` list - `fromValues` uses it to decide "no keys present at all". Miss this
   and the absent-versus-zero guarantee above quietly weakens.

Downstream, `E2eTimeline` and `E2eTimelineRecorder` copy stats field by field, and
`BenchQuickBuildMetricsSink` gives each its own JSON key.

**The analytics path has a hard cap.** Firebase allows 25 parameters per custom event
(`QuickBuildMetrics.MAX_EVENT_PARAMS`), the reload-timing bundle is already near it, and a unit
test enforces the bound. A new parameter may force merging or dropping an existing one - the
analytics path is lossy on purpose, e.g. `preSnapMillis` + `postSnapMillis` are summed into
`walk_ms` while the bench jsonl keeps them separate.

## Deploy metadata JSON (`IQuickBuildTarget.onPayload`)

Sent with every payload. The dex, resources and assets travel as ParcelFileDescriptors; this
string carries everything else the runtime needs to apply them.

```json
{"entryActivity": "com.example.app.MainActivity",
 "changedAssets": ["data/levels.json"],
 "reason": "code",
 "restart": "true"}
```

| Field | Type | Always sent | Runtime default when absent |
| --- | --- | --- | --- |
| `entryActivity` | string | yes | `null` |
| `changedAssets` | string[] | yes, empty array when no asset moved | empty list |
| `reason` | string | yes | `"unknown"` |
| `restart` | string | **only when true** | `false` |

- `entryActivity` is the fully-qualified **user** activity class to launch when no activity is
  alive.
- `changedAssets` holds relative paths (e.g. `data/levels.json`) matching the assets payload.
- `reason` is one of `code`, `resources`, `assets`, `mixed`, `forced`. The first four mirror the
  build route; `forced` is a deploy from an explicit user tap with no pending changes. It is
  logging context for the runtime, not a control signal.
- `restart` marks a restart deploy: the recompiled set touched a service, provider or custom
  `Application` class.
  - The runtime persists the payload, acks, and exits instead of hot-swapping.
  - CoGo then relaunches the launcher proxy, and the fresh process boots the persisted newest
    generation.
  - CoGo-side decision: [`domain/DeployPolicy.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/domain/DeployPolicy.kt).
    Design contract: [`docs/component-proxying-design.md`](../core/docs/component-proxying-design.md) section 4.
- **`restart` matches the exact string `"true"`.** Any other value, including `"TRUE"` or a JSON
  boolean, reads as false.

The `generation` argument travels beside this string, not inside it. The target applies a payload
only when its generation is **strictly newer** than the one it currently runs.

## Build status JSON (`IQuickBuildTarget.onBuildStatus`)

A compile error never produces a payload, so this message is how a running proxy app learns a
build failed. Its overlay then says it is still running the last working version, and a tap jumps
to the error in CoGo.

```json
{"kind": "build_failed", "file": "/abs/path/Foo.kt", "line": "12", "column": "5",
 "message": "first line of the first error", "moreErrors": "2"}
{"kind": "build_ok"}
{"kind": "building", "runningGeneration": "3"}
```

| `kind` | Field | Sent when | Runtime default when absent |
| --- | --- | --- | --- |
| `build_failed` | `file` | the shown diagnostic has one | `null` |
| `build_failed` | `line` | the shown diagnostic has one | `-1` |
| `build_failed` | `column` | the shown diagnostic has one | `-1` |
| `build_failed` | `message` | the shown diagnostic has one | `null` |
| `build_failed` | `moreErrors` | more than one error | `0`, and clamped to >= 0 |
| `build_ok` | - | - | carries no fields |
| `building` | `runningGeneration` | always | `-1` |

- **Every value is a string, including the numbers.** The runtime's hand-rolled
  [`MiniJson`](../runtime/src/main/java/com/itsaky/androidide/quickbuild/runtime/MiniJson.java)
  keeps only strings and arrays of strings; it consumes numbers, booleans, nulls and nested
  objects so a document with extra fields still parses, but **drops those keys entirely**. Send a
  JSON number and the runtime sees an absent field.
- `build_failed` reports one error, not a log: the first ERROR if there is one, else the first
  diagnostic, and only the first line of its message. `moreErrors` counts the errors beyond it.
- `build_ok` clears a shown failure and renders nothing itself.
- `building` says which generation is still on screen while a newer one compiles, so a slow build
  does not read as silence.
- `file` is a **host-side absolute path** - CoGo's filesystem, not the device app's.
- An unknown `kind` parses to `null` and is ignored, and unknown fields are ignored, so CoGo can
  extend the schema without breaking installed proxy apps.
- Malformed JSON throws, which the runtime logs and drops.

## Version skew is normal here, and each format handles it differently

The three processes are not upgraded together. A staged daemon jar can lag CoGo; the runtime AAR
is compiled **into** the proxy app, so it only changes after a proxy app rebuild and reinstall -
reinstalling CoGo alone changes nothing in a running app.

| Change | Effect on an older peer | Safe? |
| --- | --- | --- |
| Add a daemon response key | older client ignores it; newer client reads absent as null | yes, and must not bump `protocolVersion` |
| Add a daemon request field | must be optional; the codec rejects an unknown required field as malformed | yes if optional |
| Bump `protocolVersion` | `configure` aborts the session with a mismatch error | breaking, by design |
| Add a deploy-metadata or build-status field | older runtime ignores it | yes, if the value is a string |
| Add an AIDL method | append at the end only. An older stub answers "not handled", and because `IQuickBuildTarget` is `oneway` the caller never notices | yes |
| Reorder or remove an AIDL method | silently calls the wrong transaction | never do this |

So a protocol regression that compiles is not caught by any test: see the known test gap in
[README, "Re-run the whole corpus for any significant change"](../core/README.md#re-run-the-whole-corpus-for-any-significant-change).

## Key files

| File | Role |
| --- | --- |
| [`DaemonProtocol.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/DaemonProtocol.kt) | the shared request/response types, stat groups, and `PROTOCOL_VERSION` |
| [`ProtocolCodec.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/ProtocolCodec.kt) | parse and encode, pure functions over strings |
| [`RequestRouter.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/RequestRouter.kt) | dispatch plus the exception backstop that keeps a build error from killing the process |
| [`DaemonMain.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonMain.kt) | the serve loop and the stdout/stderr split |
| [`DaemonService.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonService.kt) | the stateful op implementations that fill the response values |
| [`DaemonProcessClient.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/data/DaemonProcessClient.kt) | CoGo's client: spawn, serialize, version gate, response unpacking |
| [`IQuickBuildTarget.aidl`](../runtime/src/main/aidl/com/itsaky/androidide/quickbuild/IQuickBuildTarget.aidl) | the authoritative signatures for both binder payloads |
| [`IQuickBuildHost.aidl`](../runtime/src/main/aidl/com/itsaky/androidide/quickbuild/IQuickBuildHost.aidl) | the reverse direction: `connect`, `reportReloaded`, `reportCrash`, `disconnect` |
