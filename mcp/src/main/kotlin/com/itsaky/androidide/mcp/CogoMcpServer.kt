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

private val SERVER_INSTRUCTIONS =
	"""
	Drives Code On The Go ($COGO_PACKAGE), an Android IDE that runs on the device
	itself, by shelling out to adb on the host machine.

	Tools act on whichever device adb selects by default. When several devices are
	attached, adb's own error is returned rather than a guess at which one you meant.

	A tool reporting an adb failure means the device could not be reached. That is
	not the same as an answer: it does not mean the app is absent.
	""".trimIndent()

private val NO_ARGUMENTS = ToolSchema(properties = JsonObject(emptyMap()))

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
	// ClientConnection is the receiver, not a parameter. None of these use either.
	server.addTool(
		name = "ping",
		title = "Ping",
		description = "Health check. Returns pong. Does not touch the device.",
		inputSchema = NO_ARGUMENTS,
	) { _ ->
		CallToolResult(content = listOf(TextContent("pong")))
	}

	server.addTool(
		name = "is_cogo_installed",
		title = "Is Code On The Go installed?",
		description =
			"Report whether Code On The Go ($COGO_PACKAGE) is installed on the attached device. " +
				"Reports an error, not a negative answer, when adb cannot reach a device.",
		inputSchema = NO_ARGUMENTS,
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
		inputSchema = NO_ARGUMENTS,
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
		inputSchema = NO_ARGUMENTS,
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
		inputSchema = NO_ARGUMENTS,
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
		inputSchema = NO_ARGUMENTS,
	) { _ ->
		listProjectFiles(adb)
	}

	return server
}
