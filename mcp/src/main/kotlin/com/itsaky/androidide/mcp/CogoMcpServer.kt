package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.util.Base64

const val SERVER_NAME = "cogo-mcp"
const val SERVER_VERSION = "0.1.0"
const val COGO_PACKAGE = "com.itsaky.androidide"

private val SERVER_INSTRUCTIONS =
	"""
	Drives Code On The Go ($COGO_PACKAGE), an Android IDE that runs on the device
	itself, by shelling out to adb on the host machine.

	Tools act on whichever device adb selects by default. When several devices are
	attached, adb's own error is returned rather than a guess at which one you meant.

	A tool reporting an adb failure means the device could not be reached. That is
	not the same as an answer: it does not mean the app is absent.
	""".trimIndent()

const val MAIN_ACTIVITY = "$COGO_PACKAGE/.activities.MainActivity"

private const val AUTO_OPEN_KEY = "idepref_general_autoOpenProjects"

private const val PREFS_PATH = "shared_prefs/${COGO_PACKAGE}_preferences.xml"

private const val AUTO_OPEN_ELEMENT = """<boolean name="$AUTO_OPEN_KEY" value="false" />"""

private const val PREFS_HEADER = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"""

/**
 * Returns [existingXml] with auto-open-project disabled, creating the document if
 * it is absent.
 *
 * tryOpenLastProject() falls back to the most recently modified project when no
 * last project is recorded, so clearing ide_last_project would not be enough --
 * this boolean is the only thing that reliably prevents the jump to the editor.
 *
 * The edit is element-wise, not line-wise, and so is safe to repeat: an earlier
 * version deleted whole lines on-device and destroyed the `<map>` tag whenever a
 * previous run had left it sharing a line with the boolean.
 */
fun withAutoOpenDisabled(existingXml: String): String {
	val body = existingXml.trim()
	if (body.isEmpty() || !body.contains("<map")) {
		return "$PREFS_HEADER\n<map>\n    $AUTO_OPEN_ELEMENT\n</map>\n"
	}

	val updated =
		body
			.replace(Regex("""<map\s*/>"""), "<map>\n</map>")
			.replace(Regex("""\s*<boolean\s+name="$AUTO_OPEN_KEY"[^>]*/>"""), "")
			.replace("</map>", "    $AUTO_OPEN_ELEMENT\n</map>")

	// Trailing newline kept consistent with the created-from-scratch document, so
	// feeding this function its own output is a no-op.
	return updated.trimEnd() + "\n"
}

// The prefs file is absent until the app first writes one, and that must read as
// empty rather than as an adb failure - hence the `|| true`.
private fun readPreferencesCommand() = "run-as $COGO_PACKAGE sh -c \"cat $PREFS_PATH 2>/dev/null || true\""

// base64 so the XML survives adb's argv-joining and the device shell intact.
private fun writePreferencesCommand(xml: String): String {
	val encoded = Base64.getEncoder().encodeToString(xml.toByteArray())
	return "run-as $COGO_PACKAGE sh -c \"mkdir -p shared_prefs && echo $encoded | base64 -d > $PREFS_PATH\""
}

fun cogoMcpServer(
	adb: Adb = SystemAdb(),
	// A cold start after force-stop measured ~6s on an emulator, so the budget
	// needs headroom well past that.
	homePollAttempts: Int = 30,
	homePollDelayMillis: Long = 500,
): Server {
	val server =
		Server(
			serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
			options =
				ServerOptions(
					// The tool set is fixed at construction, so there is nothing to notify about.
					capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
				),
			instructions = SERVER_INSTRUCTIONS,
		)

	// Handler is suspend ClientConnection.(CallToolRequest) -> CallToolResult: the
	// ClientConnection is the receiver, not a parameter. ping uses neither.
	server.addTool(
		name = "ping",
		title = "Ping",
		description = "Health check. Returns pong. Does not touch the device.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		CallToolResult(content = listOf(TextContent("pong")))
	}

	server.addTool(
		name = "is_cogo_installed",
		title = "Is Code On The Go installed?",
		description =
			"Report whether Code On The Go ($COGO_PACKAGE) is installed on the attached device. " +
				"Reports an error, not a negative answer, when adb cannot reach a device.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		isCogoInstalled(adb)
	}

	server.addTool(
		name = "cogo_home",
		title = "Go to Code On The Go home",
		description =
			"Bring Code On The Go to its home screen (the Get started screen) and confirm it arrived. " +
				"Force-stops the app, so unsaved editor state is lost, and permanently disables the " +
				"app's auto-open-project preference - without that the app reopens the last project " +
				"and lands in the editor instead of home.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		cogoHome(adb, homePollAttempts, homePollDelayMillis)
	}

	server.addTool(
		name = "list_projects",
		title = "List projects",
		description =
			"List the Code On The Go projects on the attached device. Scans $COGO_PROJECTS_DIR one level " +
				"deep and returns only directories the IDE would actually open, so the result is usually " +
				"shorter than a plain directory listing. Project names may contain spaces. An absent " +
				"projects directory is a plain answer, not an error. Read-only.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		listProjects(adb)
	}

	server.addTool(
		name = "list_templates",
		title = "List project templates",
		description =
			"List the project templates installed on the device - the same set the new-project wizard " +
				"offers - with each template's name and description. An empty template directory is a " +
				"plain answer, not an error: it normally means first-run onboarding has not finished. " +
				"Read-only.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		listTemplates(adb)
	}

	server.addTool(
		name = "list_project_files",
		title = "List files in the open project",
		description =
			"List the files in the project Code On The Go currently has open, as paths relative to the " +
				"project root. Generated and VCS directories (build/, .gradle/, .git/) are omitted, and a " +
				"listing over 500 files is truncated with an explicit TRUNCATED note - a truncated listing " +
				"is not the whole project. Reports plainly, without an error, when no project is open. " +
				"Read-only.",
		inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
	) { _ ->
		listProjectFiles(adb)
	}

	return server
}

private suspend fun cogoHome(
	adb: Adb,
	attempts: Int,
	delayMillis: Long,
): CallToolResult {
	// Stop first: a running app holds its preferences in memory and would write
	// them back over our edit when it exits.
	adb.run(listOf("shell", "am", "force-stop", COGO_PACKAGE)).let {
		if (it.exitCode != 0) return adbFailure(it)
	}
	val existingPrefs = adb.run(listOf("shell", readPreferencesCommand()))
	if (existingPrefs.exitCode != 0) {
		return adbFailure(existingPrefs)
	}
	adb.run(listOf("shell", writePreferencesCommand(withAutoOpenDisabled(existingPrefs.stdout)))).let {
		if (it.exitCode != 0) return adbFailure(it)
	}
	adb.run(listOf("shell", "am", "start", "-n", MAIN_ACTIVITY)).let {
		if (it.exitCode != 0) return adbFailure(it)
	}

	var resumed: String? = null
	repeat(attempts) { attempt ->
		if (attempt > 0) {
			delay(delayMillis)
		}
		val dump = adb.run(listOf("shell", "dumpsys", "activity", "activities"))
		if (dump.exitCode != 0) {
			return adbFailure(dump)
		}
		resumed = resumedActivity(dump.stdout)
		if (resumed == MAIN_ACTIVITY) {
			return CallToolResult(
				content = listOf(TextContent("Code On The Go is on its home screen (MainActivity).")),
			)
		}
	}

	val where = resumed ?: "nothing (the app never reached the foreground)"
	return CallToolResult(
		content = listOf(TextContent("Launched Code On The Go, but the foreground activity is $where, not the home screen.")),
		isError = true,
	)
}

private fun resumedActivity(dumpsys: String): String? =
	dumpsys
		.lineSequence()
		.firstOrNull { it.contains("topResumedActivity=") }
		?.substringAfter("topResumedActivity=")
		?.split(" ", "}")
		?.firstOrNull { it.contains("/") }

// Shared by every adb-backed tool: a failed call means we do not know, which is
// never the same as a negative answer.
internal fun adbFailure(result: AdbResult): CallToolResult {
	val detail = result.stderr.trim().ifEmpty { result.stdout.trim() }
	return CallToolResult(
		content = listOf(TextContent("adb failed (exit ${result.exitCode}): $detail")),
		isError = true,
	)
}

private fun isCogoInstalled(adb: Adb): CallToolResult {
	val result = adb.run(listOf("shell", "pm", "list", "packages", COGO_PACKAGE))

	// A failed adb call means we do not know, which is not the same as "not
	// installed" - report it as an error rather than a negative answer.
	if (result.exitCode != 0) {
		return adbFailure(result)
	}

	// adb shell emits CRLF, so trim before comparing.
	val installed =
		result.stdout
			.lineSequence()
			.map { it.trim() }
			.filter { it.startsWith("package:") }
			.map { it.removePrefix("package:") }
			.any { it == COGO_PACKAGE }

	val message =
		if (installed) {
			"Code On The Go ($COGO_PACKAGE) is installed."
		} else {
			"Code On The Go ($COGO_PACKAGE) is NOT installed."
		}

	return CallToolResult(content = listOf(TextContent(message)))
}
