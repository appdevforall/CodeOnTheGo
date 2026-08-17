# Quick Build wire formats (`:quickbuild:protocol`)

Quick Build runs in three processes: CoGo (the IDE), the compile daemon (a JVM child process),
and the runtime living inside the user's proxy app. This module holds the wire types the first
two share, and a change to any format below is a change to three processes at once - which are
**not** upgraded together.

Start at [`../README.md`](../README.md) for what Quick Build is and how a save flows through it.

**This is deliberately not a field reference.** Every message shape is declared in code, linked
per section. What lives here is only what the code cannot tell you: invariants, traps, and the
compatibility rules.

## Three formats, and only one of them lives in this module

| Format | Transport | Declared in |
| --- | --- | --- |
| Daemon protocol | line-delimited JSON on the daemon's stdin/stdout | [`DaemonProtocol.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/protocol/DaemonProtocol.kt) |
| Deploy metadata | `metadataJson` on `IQuickBuildTarget.onPayload` | [`PayloadDeployer.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/service/deploy/PayloadDeployer.kt) writes, [`DeployMetadata.java`](../runtime/src/main/java/com/itsaky/androidide/quickbuild/runtime/DeployMetadata.java) parses |
| Build status | `statusJson` on `IQuickBuildTarget.onBuildStatus` | [`BuildStatusJson.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/service/deploy/BuildStatusJson.kt) writes, [`BuildStatus.java`](../runtime/src/main/java/com/itsaky/androidide/quickbuild/runtime/BuildStatus.java) parses |

Only the daemon protocol has shared types, because both its ends compile against this module - and
since the wire itself is untyped JSON, the module holds the **names** too: `DaemonOps`,
`RequestKeys` and `ResponseKeys` are the op and field-name constants both ends use, so renaming one
is a compile error rather than a runtime one.

`:quickbuild:runtime` does not compile against this module, so the two binder formats are an
encoder/parser *pair*: adding a field means editing both files, and **no compiler will tell you if
you edit only one.**

## Daemon protocol: one JSON object per line, one reply per request

### Transport rules

- **Stdout carries protocol only.** [`DaemonMain`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonMain.kt)
  captures the real stdout for responses and redirects `System.out` to stderr, because the
  in-process Kotlin compiler prints to stdout and one stray line would corrupt the stream.
- **One request in flight.** CoGo serializes every call behind a mutex, and the daemon's loop is
  single-threaded on purpose.
- **Exit contract:** build errors never exit; a malformed line answers `ok:false` and the loop
  keeps serving. `shutdown` or stdin EOF exits 0. Only a fatal internal error exits non-zero,
  which CoGo reads as daemon death and respawns from.
- The daemon has no log file - progress goes to stderr, which CoGo drains and re-logs
  ([debugging.md](../docs/debugging.md#the-daemon-has-no-log-of-its-own)).

### Requests

Six ops: `configure`, `compile`, `dex`, `relink`, `ping`, `shutdown`.

- **`configure` opens the session** and fixes everything constant for it; a repeat replaces that
  state, so there is no reconfigure op. It existence-checks every classpath entry, plugin and tool,
  so a missing file fails at session start rather than mid-build. The build ops answer `ok:false`
  if no `configure` ran.
- **The caller supplies every tool path; the daemon never guesses one.** `aapt2`, `d8Jar` and
  `androidJar` are required, and `configure` answers `ok:false` with one diagnostic per missing
  field. A guessed path could compile against another SDK's `android.jar` and only fail on device.

### Responses

`{"id", "ok", <op values, flat>, "diagnostics"}`. Values are flat scalars, never nested.

- `diagnostics` **can appear on success** - a build can succeed with warnings.
- **`line` and `column` are JSON numbers, and they stop here.** CoGo keeps error positions for
  its own surfaces (Build Output, jump-to-error); the build-status format below is deliberately
  position-free.
- **An absent `classesChanged` means *unknown*; an empty array means *nothing changed*.** The
  deploy policy treats unknown conservatively; the two are not interchangeable.
- **An absent output path (`classesDir`, `dexFile`, `resourcesArsc`) fails the op.** The key is
  mandatory on purpose: a conventional fallback under `outDir` would resolve whatever the previous
  build left there, so the client would dex and deploy stale artifacts and report success with the
  user's edit missing. `resourcesArsc` is the full relinked resource **apk**, not a bare table; the
  key keeps its old name for protocol stability.

### Per-build statistics: what the op did, not just how long two compilers took

The compiler spans are only about half a warm edit `[measured on a56]`; the rest is output-tree
snapshots, the Java-ABI re-parse and per-file I/O. The counters (`CompileStats` / `DexStats`) exist
so that gap cannot be misread - javac looked like "the bottleneck" at 19-27% of a warm edit, and a
53 s first build looked like a per-edit cost when it was the cold compile seeding caches. Every
field is a counter or duration derived from no path, name or content, so the set is safe to forward
to analytics.

Two are context rather than cost, and a timing row is unreadable without them: **`compileOrdinal`**
(`1` is the cold build; a fresh `configure`, including a respawn, correctly restarts the count) and
**`scratchFsType`** (rewriting the same class tree costs ~52x more on FUSE-backed emulated storage
than on the app's own filesystem `[measured on a56]`, so durations cannot be compared across
configurations without it).

**Absent versus zero.** `fromValues` returns `null` when *none* of a group's keys are present, so an
older daemon never yields a zero-filled row reading as "measured, and free". Within a group a single
missing key defaults to `0`, indistinguishable from a measured zero.

### `protocolVersion` is a session gate, and adding a key must not bump it

CoGo aborts the session when `configure`'s `protocolVersion` differs **or is absent** - absence
fails too, because the daemon has stamped it since the protocol existed, so a missing field means
"not our daemon".

**Adding a response key is additive and must NOT bump the version.** A staged daemon jar can lag
the client that talks to it, so bumping for a new optional key would break exactly the pairing the
additive shape exists to support.

### Adding a numeric stat touches five places, and the codec is not one of them

`ProtocolCodec.encode` is generic over the values, so it needs no change. The five all sit in
`CompileStats` / `DexStats`: the property, its `KEY_*` constant, `toValues()`, `fromValues()`, and
**the private `KEYS` list** - `fromValues` uses that list to decide "no keys present at all", so
missing it quietly weakens the absent-versus-zero guarantee above.

**The analytics path has a hard cap:** Firebase allows 25 parameters per event, the reload bundle
is already near it, and a test enforces the bound. A new parameter may force merging an existing
one - the path is lossy on purpose.

## Deploy metadata JSON (`IQuickBuildTarget.onPayload`)

Sent with every payload; the dex, resources and assets travel beside it as ParcelFileDescriptors.

- `restart` marks a deploy whose recompiled set touched a service, provider or custom
  `Application`: the runtime persists, acks and exits instead of hot-swapping, and CoGo relaunches
  into the persisted generation. Decided in [`DeployPolicy.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/domain/reload/DeployPolicy.kt),
  contract in [`component-proxying-design.md`](../docs/component-proxying-design.md).
- **`restart` matches the exact string `"true"`.** Any other value - `"TRUE"`, a JSON boolean -
  reads as false.
- **The `generation` argument travels beside this string, not inside it**, and a payload applies
  only when strictly newer than the generation the app runs.

## Build status JSON (`IQuickBuildTarget.onBuildStatus`)

A compile error produces no payload, so this is how a running proxy app learns a build failed.
Kinds: `build_failed`, `build_ok`, `building`, `reinstall_pending`.

- **Every value is a string, including the numbers.** The runtime's hand-rolled
  [`MiniJson`](../runtime/src/main/java/com/itsaky/androidide/quickbuild/runtime/MiniJson.java)
  keeps only strings and arrays of strings; it consumes numbers, booleans, nulls and nested objects
  so extra fields still parse, but **drops those keys entirely** - send a JSON number and the
  runtime sees an absent field. Unknown kinds and fields are ignored, so CoGo can extend the schema
  without breaking installed proxy apps.
- `build_failed` reports one error, not a log - the first ERROR's first message line only, with
  `moreErrors` counting the rest. **No file, line or column**: the overlay is a "your build failed,
  this app is stale" warning, and locating the error is CoGo-side work, so position data never
  crosses to the device.
- `reinstall_pending` is kind-only: a rebuilt update is waiting on an install confirmation that
  Android will only show while CoGo is foregrounded, so the overlay tells the user - the one
  person the CoGo-side signals cannot reach - to switch back. The copy is static and lives
  runtime-side. An older runtime ignores the unknown kind, so the banner is simply absent there.

## Version skew is normal here, and each format handles it differently

A staged daemon jar can lag CoGo; the runtime AAR is compiled **into** the proxy app, so it only
changes after a proxy app rebuild and reinstall - reinstalling CoGo alone changes nothing in a
running app.

| Change | Effect on an older peer | Safe? |
| --- | --- | --- |
| Add a daemon response key | older client ignores it; newer client reads absent as null | yes, and must not bump `protocolVersion` |
| Add a daemon request field | must be optional; the codec rejects an unknown required field as malformed | yes if optional |
| Bump `protocolVersion` | `configure` aborts the session with a mismatch error | breaking, by design |
| Add a deploy-metadata or build-status field | older runtime ignores it | yes, if the value is a string |
| Add a build-status kind | older runtime parses it to null and drops the message; its banner is simply absent | yes |
| Remove a build-status field | older runtime reads it as absent, same as a field CoGo never populated | yes; no version to bump - the build-status format carries none |
| Add an AIDL method | append at the end only; an older `oneway` stub answers "not handled" and the caller never notices | yes |
| Reorder or remove an AIDL method | silently calls the wrong transaction | never do this |

A protocol regression that compiles is caught by no test: see the known gap in
[README, "How to Test"](../README.md#how-to-test). The name constants close the narrow half of
that gap - a renamed op or field no longer compiles on both sides - but nothing checks that a
*value's meaning* stayed the same, and the two binder formats have no shared names at all.

## Key files

| File | Role |
| --- | --- |
| [`DaemonProtocol.kt`](src/main/kotlin/org/appdevforall/cotg/quickbuild/protocol/DaemonProtocol.kt) | request/response types, stat groups, the op and field-name constants, `PROTOCOL_VERSION` |
| [`ProtocolCodec.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/ProtocolCodec.kt) | parse and encode |
| [`RequestRouter.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/protocol/RequestRouter.kt) | dispatch, plus the backstop that keeps a build error from killing the process |
| [`DaemonMain.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonMain.kt) | serve loop, stdout/stderr split |
| [`DaemonService.kt`](../daemon/src/main/kotlin/org/appdevforall/cotg/quickbuild/daemon/DaemonService.kt) | the op implementations that fill the response values |
| [`DaemonProcessClient.kt`](../core/src/main/java/org/appdevforall/cotg/quickbuild/data/DaemonProcessClient.kt) | CoGo's client: spawn, serialize, version gate, unpacking |
| [`IQuickBuildTarget.aidl`](../runtime/src/main/aidl/com/itsaky/androidide/quickbuild/IQuickBuildTarget.aidl) / [`IQuickBuildHost.aidl`](../runtime/src/main/aidl/com/itsaky/androidide/quickbuild/IQuickBuildHost.aidl) | authoritative signatures for both binder directions |
