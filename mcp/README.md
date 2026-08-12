# cogo-mcp

A host-side MCP server for driving Code On The Go from an AI coding agent.
Runs on the development machine, not on the device. Ticket: ADFA-5083.

The tool surface is deliberately small and grows one tool at a time.

| Tool | Args | Does |
|---|---|---|
| `ping` | none | Returns `pong`. Health check for the transport itself. |
| `is_cogo_installed` | none | Reports whether `com.itsaky.androidide` is installed on the attached device. |
| `cogo_home` | none | Brings CoGo to its home screen and confirms it arrived. **Destructive:** force-stops the app and permanently disables auto-open-project. |

`is_cogo_installed` reports an **error**, not `not installed`, when adb itself
fails. Not knowing is not the same as knowing the app is absent, and collapsing
the two would make a missing device look like a missing app.

### Why `cogo_home` rewrites a preference

You cannot reach home just by launching the app. `MainActivity.onCreate` calls
`tryOpenLastProject()`, and `GeneralPreferences.autoOpenProjects` defaults to
**true**, so the real path is Splash -> Onboarding -> MainActivity -> **Editor**.

Clearing `ide_last_project` is not enough either: `tryOpenLastProject()` falls
back to `validProjects.maxByOrNull { it.lastModified() }`, so it opens *some*
project regardless. Only `idepref_general_autoOpenProjects = false` prevents it.

So the tool force-stops the app (a running app holds its preferences in memory
and would write them back over the edit), reads
`shared_prefs/com.itsaky.androidide_preferences.xml` via `run-as`, rewrites just
that one key, relaunches `MainActivity` explicitly, and polls
`dumpsys activity activities` until the resumed activity is `MainActivity`.

Two consequences worth knowing: it needs a **debuggable build** for `run-as`,
and the preference change **persists** - the app will not auto-open projects
again until the user turns it back on.

The XML edit happens in Kotlin (`withAutoOpenDisabled`), not in on-device `sed`,
specifically so it can be tested. The first version used `sed '/KEY/d'` and
corrupted the file on its second run by deleting the `<map>` tag, which shared a
line with the boolean. A fake cannot round-trip a file; only a pure function can.

The explicit component is also deliberate: debug builds ship a second LAUNCHER
activity (LeakCanary), so `monkey -c LAUNCHER` is ambiguous.

## Self-description

The server describes itself to clients, and `ServerDescriptionTest` enforces it
rather than leaving it to review:

- `initialize` returns **instructions** explaining that the server drives CoGo
  over adb, that tools act on adb's default device, and that an adb failure is
  not a negative answer.
- Every tool carries a **`title`** as well as a `name` and `description`.
- `listChanged` is advertised as **`false`**. The tool set is fixed at
  construction, so claiming otherwise would promise a
  `notifications/tools/list_changed` that never arrives.

A tool's `description` is the only signal an agent gets about *when* to reach
for it, so it carries more weight than its length suggests. Adding a tool means
adding a title and a description that says when to use it - the test will fail
otherwise.

## Run

The flox environment's `on-activate` hook aborts unless it is activated from
the repo root, so activate there and `cd` in afterwards:

```bash
# from the repo root
flox activate -d flox/local -- bash -c 'cd mcp && ./gradlew run'
```

Listens on `http://127.0.0.1:3000/mcp`. Pass a different port as the first
argument: `./gradlew run --args 8080`.

Loopback only, and no TLS - there is no network hop to intercept. Binding a
non-loopback interface would require both TLS and authentication first.

## Test

```bash
# from the repo root
flox activate -d flox/local -- bash -c 'cd mcp && ./gradlew test'
```

Tests drive the server through the real MCP client over Streamable HTTP, not by
calling handlers directly. No emulator or device is required: `Adb` is a `fun
interface` over the process boundary, so tool logic is tested with fakes, and
`SystemAdb` is exercised against `/bin/echo` and `/bin/sh`.

Adding a tool that shells out? Take `Adb` as a parameter rather than calling
`ProcessBuilder` directly, or it will not be testable without a device.

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
- There is no `ktor-client-sse` artifact at any version. The client SSE plugin
  lives in `ktor-client-core`, which arrives transitively via
  `kotlin-sdk-client`.
- A tool handler is `suspend ClientConnection.(CallToolRequest) -> CallToolResult`.
  `ClientConnection` is the receiver, not a parameter.
- Root Spotless **does** format this directory. Use tabs, and run
  `./gradlew spotlessApply` from the **repo root**, not from here.
