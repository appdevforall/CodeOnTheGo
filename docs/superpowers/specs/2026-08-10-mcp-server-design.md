# ADFA-5083: CodeOnTheGo MCP server - hello world

**Status:** design approved, not implemented
**Ticket:** ADFA-5083 - "It is difficult for AI coding agents to navigate the app using low level adb commands. Let's give them a tool to be more successful."

## Goal

Stand up the smallest possible MCP server over HTTP, prove the transport end to end, and merge it. Everything of actual value gets added incrementally on top. This PR is scaffolding, deliberately.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Where the server runs | Host-side (dev machine), not in the APK | Zero APK risk, iterates independently of app releases, works against any build. Trades away privileged access to IDE internals - revisit only if a future tool actually needs it. |
| Language | Kotlin/JVM | Matches the repo's primary language. Official MCP Kotlin SDK exists and supports Streamable HTTP first-class. |
| Where the code lives | Standalone Gradle build at `mcp/`, absent from the root `settings.gradle.kts` | Exact pattern of the existing `apk-viewer-plugin/` and `markdown-preview-plugin/`. Keeps Ktor and kotlinx.serialization out of `:app`'s classpath and out of `gradle/libs.versions.toml`. |
| Transport | Streamable HTTP, `http://127.0.0.1:<port>/mcp` | Current MCP standard. SSE is deprecated and exists only for backward compatibility. |
| TLS | None | Loopback only - there is no network hop to intercept, and TLS would cost a self-signed cert plus per-client trust config for no security gain. TLS becomes mandatory the day the server binds a non-loopback interface; that is a separate ticket. |
| Tool surface | One tool, `ping` | Isolates the transport and handshake from every other concern. If it fails, the cause is unambiguous. |

### Premise worth stating

A generic `android-mcp-server` (`npx -y android-mcp-server`) is already registered in the user-scope `~/.claude.json` with `get_ui_tree`, `tap_element`, `screenshot`, and `scroll_to_element`. It overlaps ADFA-5083's stated goal.

The justification for a bespoke server is that it can be **CoGo-aware** - it can know the IDE's screens, project state, and build status rather than treating the app as an opaque view hierarchy. That is the differentiator, and it does not exist in this PR. It should be written into the ticket before PR #2 defines any real tool.

## Architecture

```
Claude Code (or any MCP client)
        |
        |  Streamable HTTP, JSON-RPC 2.0
        v
http://127.0.0.1:3000/mcp
        |
   Ktor CIO embedded server
        |
   mcpStreamableHttp { }  <- io.modelcontextprotocol:kotlin-sdk-server
        |
   Server(serverInfo, options)
        |
   tool: ping -> "pong"
```

Single process, single responsibility. No adb, no device, no state.

## Layout

```
mcp/
  settings.gradle.kts                 rootProject.name = "cogo-mcp"
  gradle/wrapper/                     Gradle 8.14.4 (matches root wrapper)
  gradlew, gradlew.bat
  build.gradle.kts                    kotlin("jvm") + application, Java 17
  src/main/kotlin/com/itsaky/androidide/mcp/Main.kt
  src/test/kotlin/com/itsaky/androidide/mcp/PingTest.kt
  README.md                           how to run, how to register
  .gitignore
```

`mcp/` is invisible to the root build. The root `settings.gradle.kts` is not touched.

## Pinned versions

Verified against Maven Central on 2026-08-10, not assumed:

| Artifact | Version | Note |
|---|---|---|
| `io.modelcontextprotocol:kotlin-sdk-server` | `0.15.0` | Latest release, published 2026-07-28 |
| `io.ktor:ktor-server-cio` | `3.5.1` | Matches the SDK's own transitive Ktor; latest is 3.5.2 |
| Kotlin | `2.4.10` | Latest stable. **Must be >= 2.4.0**: the SDK is built against `kotlin-stdlib 2.4.0`, so a 2.3.0 compiler would reject its metadata. The root repo's 2.3.0 is irrelevant here - separate build. |
| Java | `17` | Repo-wide standard (`BuildConfig.JAVA_VERSION`, `CONTRIBUTING.md`). Provided by flox; the bare shell has JDK 21. |
| Gradle | `8.14.4` | Matches the root wrapper |

The SDK does **not** pull a Ktor server engine transitively - the engine must be declared explicitly.

The kotlinx.serialization **compiler plugin is not needed**: hello-world declares no `@Serializable` classes, and the `kotlinx-serialization-json` runtime arrives transitively via `ktor-serialization-kotlinx-json`.

## Behavior

```kotlin
fun main(args: Array<String>) {
	val port = args.firstOrNull()?.toIntOrNull() ?: 3000

	val server = Server(
		serverInfo = Implementation(name = "cogo-mcp", version = "0.1.0"),
		options = ServerOptions(
			capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
		),
	)

	server.addTool(
		name = "ping",
		description = "Health check. Returns pong.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { CallToolResult(content = listOf(TextContent("pong"))) }

	embeddedServer(CIO, host = "127.0.0.1", port = port) {
		mcpStreamableHttp { server }
	}.start(wait = true)
}
```

Port is `args[0]`, defaulting to 3000. Host is hardcoded to `127.0.0.1` - binding `0.0.0.0` would expose an unauthenticated tool server to the local network, and there is no reason to.

## Verification

**Test first.** `PingTest` starts the server on an ephemeral port, connects using the SDK's own MCP **client** over Streamable HTTP, and drives the real protocol:

1. `initialize` - handshake succeeds, server reports name `cogo-mcp`
2. `tools/list` - returns exactly one tool named `ping`
3. `tools/call ping` - returns text content `"pong"`

Calling the lambda directly would prove nothing about the transport, which is the entire point of this PR.

Manual check, documented in `mcp/README.md`:

```bash
# from mcp/
flox activate -d ../flox/local -- ./gradlew run
```

## Registration

`mcp/README.md` documents the snippet; **PR #1 does not edit the tracked `.mcp.json`.**

```json
{
	"mcpServers": {
		"cogo": { "type": "http", "url": "http://127.0.0.1:3000/mcp" }
	}
}
```

Reason: `.mcp.json` is committed and shared. An `http` entry pointing at a process nobody launched makes Claude Code report a connection failure at startup for every developer on the team. Registering it becomes worthwhile once the server does something worth connecting to.

## Formatting

Root Spotless **does** cover `mcp/` - its Kotlin target is `fileTree(rootDir)` with `**/src/*/kotlin/**/*.kt`, and `kotlinGradle` targets `**/*.gradle.kts`. Neither excludes top-level standalone directories, which is why `apk-viewer-plugin/` and `markdown-preview-plugin/` are already formatted by it.

Consequences: **tabs** for indentation in all `mcp/` Kotlin and `.gradle.kts` sources, ktlint rules apply, and `./gradlew spotlessApply` runs from the **root**, not from `mcp/`.

Markdown is not a Spotless target, so this document is free-form.

## Out of scope

Explicitly deferred, in rough priority order for later PRs:

- Any adb-backed tool (`list_devices`, `screenshot`, `get_ui_tree`, `tap`, `launch_app`)
- CoGo-specific awareness: IDE screen identification, project/build state, editor contents
- TLS
- CI wiring - no workflow builds standalone directories today; `mcp/` is verified locally in PR #1. Worth a follow-up ticket.
- Authentication - unnecessary while bound to loopback, mandatory the moment it is not
- Packaging beyond `./gradlew run` (a distributable start script via `installDist`, a daemon, a launcher)

## Risks

1. **SDK API surface.** The `mcpStreamableHttp { }` builder and the `io.modelcontextprotocol.kotlin.sdk.types.*` package paths come from the SDK's README on `main`, which may be ahead of the 0.15.0 release. First implementation step is to compile against 0.15.0 and correct the imports and signatures to whatever that version actually ships. Do not assume the README matches the release.
2. **Kotlin version floor.** If the 2.4.10 toolchain causes trouble, the fallback is 2.4.0 (the SDK's own stdlib version), not 2.3.0.
3. **Spotless build-output pruning.** `buildOutputExcludes` is derived from the root build's `allprojects`, so `mcp/build/` is not pruned from the Spotless walk. Low impact - build output does not match `**/src/*/kotlin/**` - but if `spotlessCheck` starts complaining about generated files, that is the cause.

## Definition of done

- [ ] `flox activate -d flox/local -- ./gradlew run` from `mcp/` starts a server on `127.0.0.1:3000`
- [ ] `PingTest` passes: initialize, tools/list, tools/call all succeed over real Streamable HTTP
- [ ] Root `./gradlew spotlessCheck` passes
- [ ] `mcp/README.md` documents run and registration
- [ ] Root build is unaffected - `settings.gradle.kts` and `gradle/libs.versions.toml` unchanged
- [ ] PR into `stage` from `ADFA-5083-mcp`
