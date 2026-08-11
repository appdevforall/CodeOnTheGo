# CodeOnTheGo MCP Server (hello world) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a host-side MCP server reachable over HTTP that exposes exactly one tool, `ping`, and prove the transport end to end with a real MCP client.

**Architecture:** A standalone Kotlin/JVM Gradle build at `mcp/`, invisible to the root Android build. A Ktor CIO embedded server binds `127.0.0.1` and mounts the MCP SDK's Streamable HTTP route at `/mcp`. Server construction lives in a factory function separate from `main()` so tests can drive the real server over the real transport without invoking the entrypoint.

**Tech Stack:** Kotlin 2.4.10, Java 17, Gradle 8.14.4, `io.modelcontextprotocol:kotlin-sdk-server:0.15.0`, Ktor 3.5.1 (CIO engine), kotlin.test.

**Spec:** `docs/superpowers/specs/2026-08-10-mcp-server-design.md`
**Ticket:** ADFA-5083

---

## Global Constraints

- **Kotlin must be >= 2.4.0.** `kotlin-sdk-server:0.15.0` is compiled against `kotlin-stdlib 2.4.0`; a 2.3.x compiler rejects its metadata. This plan pins **2.4.10** (latest stable). The root repo's catalog pins 2.3.0 — irrelevant, this is a separate build.
- **Java 17** everywhere (`BuildConfig.JAVA_VERSION`, `CONTRIBUTING.md`).
- **Every Gradle invocation runs under flox.** The bare shell has JDK 21; flox supplies JDK 17. From `mcp/`, that is `flox activate -d ../flox/local -- ./gradlew <task>`.
- **Tabs, LF line endings.** Root Spotless reaches into `mcp/` — its Kotlin target is `fileTree(rootDir)` matching `**/src/*/kotlin/**/*.kt`, and `kotlinGradle` matches `**/*.gradle.kts`. Nothing excludes top-level standalone dirs. `spotlessApply` runs from the **repo root**, never from `mcp/`.
- **Do not modify** the root `settings.gradle.kts`, `gradle/libs.versions.toml`, or `.mcp.json`. `mcp/` stays absent from the root build, and its dependencies stay out of the shared catalog.
- **Bind `127.0.0.1` only.** Never `0.0.0.0` — this is an unauthenticated tool server.
- **No comments restating what the code says.** No separator/banner comments. ASCII only in code and code comments (`->` not the arrow glyph).
- Branch is `ADFA-5083-mcp`, already pushed. PRs target `stage`.

### Verified API reference (from the 0.15.0 jars, not the README)

The SDK's README on `main` is **ahead of the 0.15.0 release**. Use these signatures, confirmed via `javap` against `kotlin-sdk-server-jvm-0.15.0.jar`:

- **The tool handler takes TWO parameters**, not one: `suspend (ClientConnection, CallToolRequest) -> CallToolResult`. The README's single-parameter `{ request -> ... }` will not compile.
- `Server(serverInfo: Implementation, options: ServerOptions, ...)`
- `Implementation(name: String, version: String, title: String = ..., ...)`
- `ServerOptions(capabilities: ServerCapabilities, enforceStrictCapabilities: Boolean = ..., ...)`
- `ServerCapabilities(tools: ServerCapabilities.Tools? = null, ...)`; `ServerCapabilities.Tools(listChanged: Boolean?)`
- `ToolSchema(schema: String = ..., properties: JsonObject, required: List<String>? = null, ...)`
- `Server.addTool(name, description, inputSchema, title, outputSchema, annotations, execution, meta, handler)` — all but `name` and the handler have defaults; use named arguments.
- `CallToolResult(content: List<ContentBlock>, isError: Boolean? = null, ...)`; `TextContent(text: String, ...)`
- `mcpStreamableHttp` is an extension on **`Application`** (so it goes directly inside the `embeddedServer { }` lambda, not inside a `routing { }` block): `Application.mcpStreamableHttp(path: String = "/mcp", ..., block: (RoutingContext) -> Server)`
- Client side: `HttpClient.mcpStreamableHttpTransport(url: String, ...): StreamableHttpClientTransport`, `Client(Implementation, ClientOptions = ...)`, `Client.connect(Transport)`, `Client.listTools(): ListToolsResult` (`.tools`), `Client.callTool(name: String, arguments: Map<String, Any?>): CallToolResult`

---

## File Structure

| File | Responsibility |
|---|---|
| `mcp/settings.gradle.kts` | Names the standalone build `cogo-mcp`. Nothing else. |
| `mcp/build.gradle.kts` | Kotlin JVM + application plugin, Java 17, the four dependencies. |
| `mcp/gradle/wrapper/*`, `mcp/gradlew`, `mcp/gradlew.bat` | Gradle 8.14.4 wrapper, copied from the repo root. |
| `mcp/.gitignore` | `build/`, `.gradle/`. |
| `mcp/src/main/kotlin/com/itsaky/androidide/mcp/CogoMcpServer.kt` | `cogoMcpServer(): Server` - builds the MCP server and registers tools. No transport, no I/O. This is the unit tests construct. |
| `mcp/src/main/kotlin/com/itsaky/androidide/mcp/Main.kt` | `main(args)` - parses the port, starts Ktor, mounts the MCP route. Wiring only. |
| `mcp/src/test/kotlin/com/itsaky/androidide/mcp/SdkResolutionTest.kt` | Smoke test: the SDK resolves and its metadata is readable by this compiler. |
| `mcp/src/test/kotlin/com/itsaky/androidide/mcp/PingTest.kt` | End-to-end: real MCP client, real Streamable HTTP, initialize -> tools/list -> tools/call. |
| `mcp/README.md` | How to run it, how to register it. |

The `CogoMcpServer.kt` / `Main.kt` split is the one structural decision here. It exists so `PingTest` can mount the identical server the entrypoint mounts, without `main()`'s `wait = true` blocking the test thread. As tools accumulate, `CogoMcpServer.kt` splits by tool group and `Main.kt` never changes.

---

### Task 1: Standalone Gradle build that resolves the SDK

Isolates the single riskiest thing in this plan — the Kotlin 2.4.0 metadata floor — so that if it breaks, the failure is unambiguous and unmixed with protocol problems.

**Files:**
- Create: `mcp/settings.gradle.kts`
- Create: `mcp/build.gradle.kts`
- Create: `mcp/.gitignore`
- Create: `mcp/gradle/wrapper/gradle-wrapper.properties`, `mcp/gradle/wrapper/gradle-wrapper.jar`, `mcp/gradlew`, `mcp/gradlew.bat` (copied)
- Test: `mcp/src/test/kotlin/com/itsaky/androidide/mcp/SdkResolutionTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a working `mcp/` Gradle build with `io.modelcontextprotocol:kotlin-sdk-server:0.15.0`, `io.ktor:ktor-server-cio:3.5.1`, `io.modelcontextprotocol:kotlin-sdk-client:0.15.0` (test), `io.ktor:ktor-client-cio:3.5.1` (test), `io.ktor:ktor-client-sse:3.5.1` (test), and `kotlin("test")` on the classpath. `application { mainClass = "com.itsaky.androidide.mcp.MainKt" }`.

- [ ] **Step 1: Copy the Gradle wrapper from the repo root**

```bash
cd /Users/eisen/src/CodeOnTheGo
mkdir -p mcp/gradle/wrapper mcp/src/main/kotlin/com/itsaky/androidide/mcp mcp/src/test/kotlin/com/itsaky/androidide/mcp
cp gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties mcp/gradle/wrapper/
cp gradlew gradlew.bat mcp/
chmod +x mcp/gradlew
grep distributionUrl mcp/gradle/wrapper/gradle-wrapper.properties
```

Expected: `distributionUrl=...gradle-8.14.4-all.zip`

- [ ] **Step 2: Write `mcp/settings.gradle.kts`**

Tabs, not spaces.

```kotlin
rootProject.name = "cogo-mcp"
```

- [ ] **Step 3: Write `mcp/build.gradle.kts`**

Tabs, not spaces. `jvmToolchain(17)` is deliberate: under flox the running JVM is already 17 so it resolves locally, and outside flox it fails with an explicit toolchain error rather than silently compiling against JDK 21.

```kotlin
plugins {
	kotlin("jvm") version "2.4.10"
	application
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
	implementation("io.ktor:ktor-server-cio:3.5.1")

	testImplementation(kotlin("test"))
	testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
	testImplementation("io.ktor:ktor-client-cio:3.5.1")
	testImplementation("io.ktor:ktor-client-sse:3.5.1")
}

kotlin {
	jvmToolchain(17)
}

application {
	mainClass.set("com.itsaky.androidide.mcp.MainKt")
}

tasks.test {
	useJUnitPlatform()
}
```

- [ ] **Step 4: Write `mcp/.gitignore`**

```
build/
.gradle/
```

- [ ] **Step 5: Write the failing test**

`mcp/src/test/kotlin/com/itsaky/androidide/mcp/SdkResolutionTest.kt` — tabs.

```kotlin
package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlin.test.Test
import kotlin.test.assertEquals

class SdkResolutionTest {
	@Test
	fun `sdk types load under this kotlin version`() {
		val info = Implementation(name = "cogo-mcp", version = "0.1.0")
		assertEquals("cogo-mcp", info.name)
		assertEquals("0.1.0", info.version)
	}
}
```

- [ ] **Step 6: Run the test**

```bash
cd /Users/eisen/src/CodeOnTheGo/mcp
flox activate -d ../flox/local -- ./gradlew test --tests '*SdkResolutionTest*'
```

Expected: **PASS**. This test has no red phase — it asserts the build resolves, and a build that cannot resolve fails to compile rather than failing an assertion.

If it fails with `Class 'Implementation' was compiled with an incompatible version of Kotlin`, the Kotlin version is below the 2.4.0 floor — check the `kotlin("jvm") version` string. If it fails with a toolchain error, the shell is not inside flox.

- [ ] **Step 7: Confirm the root build is untouched**

```bash
cd /Users/eisen/src/CodeOnTheGo
git status --short settings.gradle.kts gradle/libs.versions.toml .mcp.json
```

Expected: **no output**.

- [ ] **Step 8: Format and commit**

```bash
cd /Users/eisen/src/CodeOnTheGo
flox activate -d flox/local -- ./gradlew spotlessApply
git add mcp/
git commit -m "ADFA-5083: Standalone Gradle build for the MCP server

Kotlin 2.4.10 is a floor, not a preference: kotlin-sdk-server 0.15.0 ships
stdlib 2.4.0 metadata that a 2.3.x compiler rejects. Standalone build keeps
that off the root catalog entirely."
```

---

### Task 2: The ping tool, proven over real Streamable HTTP

**Files:**
- Create: `mcp/src/main/kotlin/com/itsaky/androidide/mcp/CogoMcpServer.kt`
- Create: `mcp/src/main/kotlin/com/itsaky/androidide/mcp/Main.kt`
- Test: `mcp/src/test/kotlin/com/itsaky/androidide/mcp/PingTest.kt`

**Interfaces:**
- Consumes: the Task 1 build and its classpath.
- Produces:
  - `fun cogoMcpServer(): Server` in `com.itsaky.androidide.mcp` - returns a configured `Server` with the `ping` tool registered. Takes no arguments; starts no transport.
  - `const val DEFAULT_PORT: Int = 3000` in `com.itsaky.androidide.mcp`
  - `fun main(args: Array<String>)` in `Main.kt` (compiled class `com.itsaky.androidide.mcp.MainKt`)

- [ ] **Step 1: Write the failing test**

`mcp/src/test/kotlin/com/itsaky/androidide/mcp/PingTest.kt` — tabs. Note `port = 0` for an ephemeral port, then `resolvedConnectors()` to discover what was actually bound; hardcoding 3000 in a test makes it fail whenever a real server is running.

```kotlin
package com.itsaky.androidide.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer

class PingTest {
	private fun <T> withConnectedClient(block: suspend (Client) -> T): T =
		runBlocking {
			val engine =
				embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
					mcpStreamableHttp { cogoMcpServer() }
				}.start(wait = false)
			try {
				val port = engine.engine.resolvedConnectors().first().port
				val http = HttpClient(ClientCIO) { install(SSE) }
				try {
					val client = Client(Implementation(name = "cogo-mcp-test", version = "0.1.0"))
					client.connect(http.mcpStreamableHttpTransport("http://127.0.0.1:$port/mcp"))
					block(client)
				} finally {
					http.close()
				}
			} finally {
				engine.stop(gracePeriodMillis = 0, timeoutMillis = 2000)
			}
		}

	@Test
	fun `handshake reports the server identity`() =
		withConnectedClient { client ->
			assertEquals("cogo-mcp", client.serverVersion?.name)
		}

	@Test
	fun `tools list contains exactly ping`() =
		withConnectedClient { client ->
			val tools = client.listTools().tools
			assertEquals(listOf("ping"), tools.map { it.name })
		}

	@Test
	fun `calling ping returns pong`() =
		withConnectedClient { client ->
			val result = client.callTool(name = "ping", arguments = emptyMap())
			val text = result.content.filterIsInstance<TextContent>().single().text
			assertEquals("pong", text)
		}
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/eisen/src/CodeOnTheGo/mcp
flox activate -d ../flox/local -- ./gradlew test --tests '*PingTest*'
```

Expected: **compilation failure**, `Unresolved reference: cogoMcpServer`. That is the correct red phase — the test names a function that does not exist yet.

- [ ] **Step 3: Write `CogoMcpServer.kt`**

The handler's two parameters are both unused here; that is the real 0.15.0 signature (`ClientConnection`, `CallToolRequest`), and the README's one-parameter form does not compile.

```kotlin
package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

const val SERVER_NAME = "cogo-mcp"
const val SERVER_VERSION = "0.1.0"

fun cogoMcpServer(): Server {
	val server =
		Server(
			serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
			options =
				ServerOptions(
					capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
				),
		)

	server.addTool(
		name = "ping",
		description = "Health check. Returns pong.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _, _ ->
		CallToolResult(content = listOf(TextContent("pong")))
	}

	return server
}
```

- [ ] **Step 4: Write `Main.kt`**

```kotlin
package com.itsaky.androidide.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

const val DEFAULT_PORT = 3000

fun main(args: Array<String>) {
	val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT

	// Loopback only: this server is unauthenticated.
	embeddedServer(CIO, host = "127.0.0.1", port = port) {
		mcpStreamableHttp { cogoMcpServer() }
	}.start(wait = true)
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd /Users/eisen/src/CodeOnTheGo/mcp
flox activate -d ../flox/local -- ./gradlew test
```

Expected: **4 tests pass** (1 from `SdkResolutionTest`, 3 from `PingTest`).

Two known failure modes, both with a determinate fix:
- `NoTransformationFoundException` or a hang on `connect` — the Ktor **client** needs the SSE plugin. It is installed in the test above; if the error persists, confirm `io.ktor:ktor-client-sse:3.5.1` is on the test classpath.
- A 404 on `/mcp` — the route path default differs from `/mcp`. Pass it explicitly: `mcpStreamableHttp(path = "/mcp") { cogoMcpServer() }`.

- [ ] **Step 6: Verify the server runs for real**

```bash
cd /Users/eisen/src/CodeOnTheGo/mcp
flox activate -d ../flox/local -- ./gradlew run &
sleep 15
curl -sS -i -X POST http://127.0.0.1:3000/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
```

Expected: HTTP 200 with a JSON-RPC result naming `cogo-mcp`, and an `Mcp-Session-Id` response header. Stop the background server afterward (`kill %1`).

- [ ] **Step 7: Format and commit**

```bash
cd /Users/eisen/src/CodeOnTheGo
flox activate -d flox/local -- ./gradlew spotlessApply
git add mcp/
git commit -m "ADFA-5083: Add the ping tool and prove the transport end to end

PingTest drives the real MCP client over real Streamable HTTP - initialize,
tools/list, tools/call - rather than calling the handler directly, since the
transport is the only thing this PR actually adds.

The tool handler takes (ClientConnection, CallToolRequest); the SDK README's
single-parameter form is ahead of the 0.15.0 release and does not compile."
```

---

### Task 3: Documentation and ship

**Files:**
- Create: `mcp/README.md`

**Interfaces:**
- Consumes: the working server from Task 2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write `mcp/README.md`**

````markdown
# cogo-mcp

A host-side MCP server for driving Code On The Go from an AI coding agent.
Runs on the development machine, not on the device. Ticket: ADFA-5083.

Right now it exposes exactly one tool, `ping`. That is deliberate - this is
scaffolding that proves the transport. adb-backed tools land incrementally.

## Run

```bash
# from mcp/
flox activate -d ../flox/local -- ./gradlew run
```

Listens on `http://127.0.0.1:3000/mcp`. Pass a different port as the first
argument: `./gradlew run --args 8080`.

Loopback only, and no TLS - there is no network hop to intercept. Binding a
non-loopback interface would require both TLS and authentication first.

## Test

```bash
flox activate -d ../flox/local -- ./gradlew test
```

## Register with an MCP client

Not registered automatically. `.mcp.json` is committed and shared, and an
`http` entry pointing at a process nobody started makes Claude Code report a
connection failure at startup for every developer on the team. Add it locally
once you are actually using it:

```json
{
	"mcpServers": {
		"cogo": { "type": "http", "url": "http://127.0.0.1:3000/mcp" }
	}
}
```

## Notes for contributors

- This is a **standalone Gradle build**. It is absent from the root
  `settings.gradle.kts`, and its dependencies are declared inline rather than
  in `gradle/libs.versions.toml` - the same pattern as `apk-viewer-plugin/`.
- **Kotlin 2.4.10 is a floor.** `kotlin-sdk-server:0.15.0` ships
  `kotlin-stdlib 2.4.0` metadata that the root catalog's 2.3.0 compiler
  rejects. Do not "align" this with the root version.
- Root Spotless **does** format this directory. Use tabs, and run
  `./gradlew spotlessApply` from the **repo root**, not from here.
````

- [ ] **Step 2: Full verification from a clean build**

```bash
cd /Users/eisen/src/CodeOnTheGo/mcp
flox activate -d ../flox/local -- ./gradlew clean test
cd /Users/eisen/src/CodeOnTheGo
flox activate -d flox/local -- ./gradlew spotlessCheck
```

Expected: tests pass, `spotlessCheck` passes. Do not proceed past a failure here.

If `spotlessCheck` reports violations in files under `mcp/build/`, the cause is known: the root Spotless config derives its build-output exclusions from the root build's `allprojects`, and `mcp` is not one of them, so `mcp/build/` is not pruned from the walk. Fix by running `./gradlew clean` in `mcp/` before `spotlessCheck` from the root. Do **not** add `mcp/**` to the root `commonTargetExcludes` — that would exempt the source too.

- [ ] **Step 3: Confirm the root build is still untouched**

```bash
cd /Users/eisen/src/CodeOnTheGo
git diff --stat origin/stage...HEAD -- settings.gradle.kts gradle/libs.versions.toml .mcp.json
git diff --stat origin/stage...HEAD | tail -3
```

Expected: the first command prints **nothing**; the second shows only `docs/` and `mcp/` files.

- [ ] **Step 4: Commit and push**

```bash
git add mcp/README.md
git commit -m "ADFA-5083: Document how to run and register the MCP server"
git push
```

- [ ] **Step 5: Open the PR into stage**

Write the body to a tempfile first — CLAUDE.md forbids inline heredocs for anything with special characters. Write exactly this to `/tmp/pr-body.md` using the Write tool:

```markdown
Stands up the smallest MCP server that proves anything, so the useful tools can
land incrementally on top of a transport that already works.

## What this is

A host-side Kotlin/JVM MCP server in a standalone Gradle build at `mcp/`,
serving Streamable HTTP on `127.0.0.1:3000/mcp`. It exposes exactly one tool,
`ping`. That is the whole scope.

Host-side rather than in-APK: nothing ships to the device, so this carries no
app risk and iterates independently of releases.

## Review notes

- **The root build is untouched.** `settings.gradle.kts`,
  `gradle/libs.versions.toml`, and `.mcp.json` are unchanged. `mcp/` is
  invisible to the Android build, the same way `apk-viewer-plugin/` is.
- **Kotlin 2.4.10 here is a floor, not drift.** `kotlin-sdk-server:0.15.0`
  ships `kotlin-stdlib 2.4.0` metadata that the catalog's 2.3.0 compiler
  rejects outright. The standalone layout is what makes that harmless.
- **`.mcp.json` is deliberately not modified.** It is committed and shared; an
  `http` entry aimed at a process nobody started makes Claude Code report a
  connection failure at startup for every developer. `mcp/README.md` documents
  the snippet for local use.
- **No TLS.** Loopback only, so there is no hop to intercept. Binding a
  non-loopback interface would require TLS and authentication first, and gets
  its own ticket.
- **Tests drive the real protocol.** `PingTest` starts the server on an
  ephemeral port and connects with the SDK's own MCP client over Streamable
  HTTP - initialize, tools/list, tools/call. Calling the handler directly would
  prove nothing about the only thing this PR adds.

## Next

adb-backed tools, then CoGo-specific awareness (IDE screens, project and build
state). Open question worth settling first: a generic `android-mcp-server`
already exists, so the case for a bespoke one rests on CoGo-awareness rather
than a generic view hierarchy.

Design spec: `docs/superpowers/specs/2026-08-10-mcp-server-design.md`
Plan: `docs/superpowers/plans/2026-08-11-mcp-server-hello-world.md`
```

```bash
gh pr create --base stage \
  --title "ADFA-5083: Minimal HTTP MCP server (hello world)" \
  --body-file /tmp/pr-body.md
```

- [ ] **Step 6: Comment on the ticket**

```bash
jira issue comment add ADFA-5083 "PR opened into stage. Hello-world server is green: initialize, tools/list and tools/call ping all verified over real Streamable HTTP. Next up: adb-backed tools."
```

---

## Definition of Done

- [ ] `flox activate -d ../flox/local -- ./gradlew run` from `mcp/` serves `127.0.0.1:3000/mcp`
- [ ] All 4 tests pass from a clean build
- [ ] Root `./gradlew spotlessCheck` passes
- [ ] `mcp/README.md` documents run, test, and registration
- [ ] Root `settings.gradle.kts`, `gradle/libs.versions.toml`, and `.mcp.json` are unchanged
- [ ] PR open against `stage`, ticket commented

## Deliberately Not In This Plan

adb-backed tools, CoGo-specific awareness (IDE screens, project/build state), TLS, authentication, CI wiring for standalone directories, and any packaging beyond `./gradlew run`. Each is a follow-up.

**One open question for the team, from the spec:** a generic `android-mcp-server` is already registered in `~/.claude.json` with `get_ui_tree`, `tap_element`, and `screenshot`. The justification for a bespoke server is CoGo-awareness rather than a generic view hierarchy. Settle that before PR #2 defines any real tool.
