# SQLite debugger for apps

A debug-only SQLite debugger and MCP server that runs inside apps built with Code on the Go (ADFA-7131). It lets an AI agent list an app's databases, read their schema and rows, see the SQL the app runs, and check integrity, all read-only. Phase 1 is the native extension (ADFA-7135) and the server (ADFA-7136). Nothing in CoGo itself depends on it: apps do, in debug builds.

The module builds an AAR with `libsqlitedbg.so` for arm64-v8a and armeabi-v7a in one artifact, so it has no v7/v8 flavors (`AndroidModuleConf`). Its minSdk is 21, requery's, because the debugger runs on requery's SQLite. The unit test task is `:subprojects:sqlite-debugger:testDebugUnitTest`.

## How it fits together

```
MCP client --HTTP, 127.0.0.1--> McpHttp -> McpProtocol -> DatabaseTools -> DebugEngine
                                                                              |
                                                          NativeDebugEngine -> NativeBridge (JNI)
                                                                              |
                                                          sqlitedbg_jni.c -> sqlitedbg.c (sqlitedbg.h)
                                                                              |
                                                          requery's libsqlite3x.so (the app's SQLite)
```

- `src/main/cpp/sqlitedbg.h` is the contract between the extension, its JNI layer and its host tests, with the semantics of each call. `SQLITEDBG_INTERFACE_VERSION` is raised when anything in it changes shape.
- `sqlitedbg.c` is a SQLite extension. `sqlitedbg_install` registers it with `sqlite3_auto_extension`, so it runs on every connection the app opens afterwards. It keeps the list of app connections and a ring buffer of statement events (trace: statement, profile, close), and opens connections for the server (`sqlitedbg_open_server`).
- `sqlitedbg_jni.c` finds `sqlite3_auto_extension` in requery's `libsqlite3x.so` with `dlopen(RTLD_NOLOAD)` and `dlsym`, so no SQLite is compiled or linked here (CMake takes only the headers from `../sqlite-js/upstream/libs`). Its version script exports only `JNI_OnLoad`; the functions of `NativeBridge` are registered there by name, and `consumer-rules.pro` keeps them.
- `engine/`: `DebugEngine` is what the server needs from the extension, `NativeDebugEngine` implements it over JNI, and `EventDecoder` turns the event buffer's bytes into `Event`s.
- `mcp/`: `McpHttp` is the HTTP/1.1 listener, `McpProtocol` is JSON-RPC and the MCP session handling, `DatabaseTools` is the tools: `list_databases`, `list_connections`, `get_schema`, `query`, `explain_query_plan`, `get_stats`, `integrity_check`, `get_events`.
- `DebuggerStartup` is a `ContentProvider` with the highest `initOrder`, declared in the AAR's manifest. It runs before the app's own providers and `Application.onCreate`, so the extension is registered before the app opens a database. It installs the extension synchronously in `onCreate` (so an app that opens its database in `Application.onCreate` is still seen) and starts the server on a background thread. Everything after the debuggable check is wrapped in `catch (Throwable)`: a missing requery library (`UnsatisfiedLinkError`) means the debugger is disabled and logged, never a crash of the host app.

### The event record

The layout of `sqlitedbg_event_record` lives in `sqlitedbg.h`. The JNI layer hands the bytes to Kotlin unchanged, so `EventDecoder.kt` mirrors the layout, field offsets included: change both together. A committed fixture pins them (see Testing).

## Decisions that shape it

- **Debug builds only.** `DebuggerStartup.onCreate` throws if the process is not debuggable, so a release build that includes the library by mistake crashes at once instead of opening a local server.
- **Loopback HTTP, a random port and token per start.** `McpHttp` binds 127.0.0.1 only (port 0, so the system picks one) and requires `Authorization: Bearer <token>`, a new 32-byte random token each process start. It also checks that the Host and any Origin name 127.0.0.1 or localhost, against DNS rebinding. At most 8 connections at once. It opens no event stream: GET gets 405.
- **Read-only is a guard against accidents, not a security boundary.** Every tool opens its server connection with `writable = false`: `SQLITE_OPEN_READONLY`, `PRAGMA query_only` and an authorizer that refuses ordinary writes, transactions and savepoints, and `PRAGMA locking_mode` and `busy_timeout`, so a client cannot hold a lock on the app's database; the JNI query path also rolls back any transaction left open. Anyone holding the token can still run SQL, so the token is the boundary.
- **Only databases an app connection has open.** `DatabaseTools` refuses any other path before the engine is called: opening a file Android's own SQLite has open, with requery's, can corrupt it. The extension has no SQLite API until an app connection has opened, so `sqlitedbg_open_server` returns `SQLITE_MISUSE` before that.
- **The app's connections are never altered.** The extension never sets the progress handler or the update hook of an app connection: requery sets and clears the progress handler around cancellable statements, and the update hook belongs to the app. Only server connections use a progress handler, for the time limit.
- **Bound values are recorded only while a client is connected.** Expanding the SQL costs memory on every run, so `sqlitedbg_set_client_connected` turns it on; the first `get_events` call does, and it goes off again 60 s after the last one (checked on every tool call and by a timer).
- **Limits.** Connections opened before `install` are not seen, so `DebuggerStartup` runs as early as the process allows. Events are dropped, oldest first, when the buffer (4 MiB by default) is full, and `get_events` reports how many. A query result holds at most 4 MiB of cell bytes (`QueryLimits.maxTotalBytes`: TEXT and BLOB bytes kept, 8 per number), so a wide SELECT cannot exhaust the app's heap; the rest is paged (`has_more`). The engine reports which cells were cut (`QueryResult.truncatedCellIndexes`), so a BLOB of exactly the limit is not flagged.

## Testing

- **Host C tests of the extension:** `subprojects/sqlite-debugger/test.sh [--update-fixture] [AMALGAMATION_TARBALL]`. It builds SQLite 3.49.1 from the amalgamation as requery builds it (`SQLITE_THREADSAFE=2`), checks its headers against `sqlite-js/upstream/libs`, and runs `src/test/c/sqlitedbg_test.c` under AddressSanitizer with UBSan, then under ThreadSanitizer. It needs gcc, curl, tar and sha256sum, and downloads the tarball unless you pass one.
- **The fixture:** `src/test/resources/.../engine/events.bin` and `events.tsv` are written by the C test and read by `EventDecoderTest`. Without `--update-fixture`, `test.sh` checks the committed pair against the current record layout, so a layout change fails until you regenerate it and update `EventDecoder.kt`.
- **JVM tests:** `flox activate -d flox/local -- ./gradlew :subprojects:sqlite-debugger:testDebugUnitTest`. They cover `EventDecoder`, `McpProtocol` and `DatabaseTools` (with `FakeDebugEngine`).

No workflow in `.github/workflows` runs either yet.

## Not done yet

- Broadcasting the port and token to Code on the Go (ADFA-7137). `DebuggerStartup` only keeps them in `DebuggerStartup.port` and `endpointToken` and logs the port, never the token.
- Gradle plugin wiring and the build-time rewrite that adds the library to debug builds (ADFA-7138).
- The AI-Agent-MCP side that connects to the server (ADFA-7139).
- Verification on a device. The C tests run on the host and the Kotlin tests on the JVM; nothing has run in an app on a device or emulator.
