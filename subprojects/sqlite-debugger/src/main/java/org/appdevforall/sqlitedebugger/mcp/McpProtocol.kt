package org.appdevforall.sqlitedebugger.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A tool an MCP client can call. [run] gets the call's arguments (an empty object when none were
 * sent) and the time limit of its POST (see [McpProtocol.postTimeLimitMs]; null when there is none),
 * and returns what happened, as JSON; it throws [McpToolError] for a failure the client should
 * read, such as "a person is needed".
 */
class McpTool(
	val name: String,
	val title: String,
	val description: String,
	val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false),
	/** Only reports; changes nothing. Shown to clients as a hint, not relied on. */
	val readOnly: Boolean = true,
	/** May delete or overwrite data. Meaningful only when not [readOnly]; a hint like it. */
	val destructive: Boolean = false,
	val run: (args: JSONObject, deadlineMs: Long?) -> JSONObject,
) {
	fun describe(): JSONObject =
		JSONObject()
			.put("name", name)
			.put("title", title)
			.put("description", description)
			.put("inputSchema", inputSchema)
			.put(
				"annotations",
				JSONObject()
					.put("title", title)
					.put("readOnlyHint", readOnly)
					.put("destructiveHint", destructive)
					.put("openWorldHint", false),
			)
}

/** A tool's failure, told to the client as a tool result with isError, with [details] when there are any. */
class McpToolError(
	message: String,
	val details: JSONObject? = null,
) : Exception(message)

/**
 * The Model Context Protocol, as the SQLite debugger speaks it: JSON-RPC 2.0 over the Streamable HTTP
 * transport, answering every POST with plain JSON, never an event stream. Plain Kotlin and org.json,
 * so unit tests run it without Android. [McpHttp] does the sockets and checks who's asking; this
 * decides what to answer, and keeps the sessions.
 *
 * Methods: initialize, ping, tools/list, tools/call. Notifications, and responses a client sends,
 * are accepted and need no answer (HTTP 202).
 */
class McpProtocol(
	private val tools: List<McpTool>,
	private val serverVersion: String,
	private val newSessionId: () -> String,
	private val maxSessions: Int = 16,
	/**
	 * How long one POST may spend in tools, from when [post] starts. Every tool call of a batch gets the same
	 * deadline (start plus this, by [clock]), so a batch of slow calls cannot hold the tools past what its
	 * client waits for. null: no limit is set, and a tool applies its own.
	 */
	private val postTimeLimitMs: Long? = null,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	companion object {
		/** Newest first: offered when a client asks for a version this server doesn't know. */
		val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
		const val SERVER_NAME = "sqlite-debugger"
		const val SESSION_HEADER = "Mcp-Session-Id"
		const val PARSE_ERROR = -32700
		const val INVALID_REQUEST = -32600
		const val METHOD_NOT_FOUND = -32601
		const val INVALID_PARAMS = -32602

		/** JSON-RPC's range for errors the server defines: a batch ran past its reply budget. */
		const val BATCH_BUDGET_EXCEEDED = -32000

		/** Most messages in one batch. A client that needs more sends several POSTs. */
		const val MAX_BATCH_MESSAGES = 32

		/**
		 * Once the replies of a batch add up to this many characters, its remaining requests are answered with
		 * [BATCH_BUDGET_EXCEEDED] without running. So a batch holds at most this plus one reply in the host app's
		 * heap, and that one reply can be several times this size. A tool result holds up to 4 MiB of cell bytes
		 * (more only when its first row alone is wider: the first row always comes back whole), sent as text and
		 * again as structured content, but that cap counts cell bytes, not JSON: org.json writes most control
		 * characters as a six-character escape such as `\u0001`, which the text copy escapes again, so a TEXT cell
		 * of them takes about 13 characters per byte. A NULL cell counts no bytes but is still sent.
		 */
		const val MAX_BATCH_REPLY_CHARS = 8 * 1024 * 1024
		const val INSTRUCTIONS =
			"Inspects and queries the SQLite databases of the app this server runs in, a debug " +
				"build made with Code on the Go. Every tool is read-only."
	}

	/** What to send back: an HTTP status, a JSON body (or none), and extra headers. */
	class Reply(
		val status: Int,
		val body: String?,
		val headers: Map<String, String> = emptyMap(),
	)

	/** Live sessions, oldest first; the oldest is dropped when there are too many. */
	private val sessions = LinkedHashSet<String>()

	/** A POST to the endpoint: one JSON-RPC message, or a batch of them. */
	fun post(
		body: String,
		sessionId: String?,
		versionHeader: String?,
	): Reply {
		val deadline = postTimeLimitMs?.let { clock() + it }
		val parsed =
			try {
				JSONTokener(body).nextValue()
			} catch (e: Exception) {
				null
			}
		if (parsed !is JSONObject && parsed !is JSONArray) {
			return Reply(400, error(null, PARSE_ERROR, "That isn't a JSON object or array.").toString())
		}
		val messages: List<Any?> = if (parsed is JSONArray) (0 until parsed.length()).map { parsed.opt(it) } else listOf(parsed)
		if (messages.isEmpty()) return Reply(400, error(null, INVALID_REQUEST, "An empty batch.").toString())

		if (messages.any { (it as? JSONObject)?.optString("method") == "initialize" }) {
			if (messages.size != 1) {
				return Reply(400, error(null, INVALID_REQUEST, "Send initialize on its own, not in a batch.").toString())
			}
			return initialize(messages[0] as JSONObject)
		}
		if (sessionId.isNullOrEmpty()) {
			return Reply(400, error(null, INVALID_REQUEST, "No $SESSION_HEADER header: send initialize first.").toString())
		}
		if (!touch(sessionId)) {
			return Reply(404, error(null, INVALID_REQUEST, "That session has ended or doesn't exist: send initialize again.").toString())
		}
		if (versionHeader != null && versionHeader !in VERSIONS) {
			val detail = "Unsupported MCP-Protocol-Version $versionHeader; this server speaks ${VERSIONS.joinToString()}."
			return Reply(400, error(null, INVALID_REQUEST, detail).toString())
		}
		if (messages.size > MAX_BATCH_MESSAGES) {
			// Nothing has run: refusing the batch whole beats guessing which messages the client meant to keep.
			val detail = "A batch holds at most $MAX_BATCH_MESSAGES messages; send ${messages.size} in several POSTs."
			return Reply(400, error(null, INVALID_REQUEST, detail).toString())
		}
		val replies = mutableListOf<JSONObject>()
		var chars = 0L
		for (message in messages) {
			val reply =
				if (chars >= MAX_BATCH_REPLY_CHARS) {
					skipped(message)
				} else {
					handle(message, deadline)
				}
			if (reply == null) continue
			// The size is counted as the reply is made, because it all stays in memory until the batch is sent.
			chars += reply.toString().length
			replies += reply
		}
		if (replies.isEmpty()) return Reply(202, null)
		return Reply(200, if (parsed is JSONArray) JSONArray(replies).toString() else replies[0].toString())
	}

	/** A DELETE: the client is done with its session. */
	fun delete(sessionId: String?): Reply {
		if (sessionId.isNullOrEmpty()) return Reply(400, error(null, INVALID_REQUEST, "No $SESSION_HEADER header.").toString())
		val removed = synchronized(sessions) { sessions.remove(sessionId) }
		return if (removed) {
			Reply(204, null)
		} else {
			Reply(404, error(null, INVALID_REQUEST, "That session has ended or doesn't exist.").toString())
		}
	}

	/** Ends every session, so clients must initialize again. */
	fun endAll() = synchronized(sessions) { sessions.clear() }

	fun sessionCount(): Int = synchronized(sessions) { sessions.size }

	private fun touch(id: String): Boolean =
		synchronized(sessions) {
			if (!sessions.remove(id)) return false
			sessions += id // most recently used goes last, so it's dropped last
			true
		}

	private fun initialize(m: JSONObject): Reply {
		val id = m.opt("id")
		if (!validId(id)) return Reply(400, error(null, INVALID_REQUEST, "initialize needs an id, a string or a number.").toString())
		if (m.optString("jsonrpc") != "2.0") return Reply(400, error(id, INVALID_REQUEST, "jsonrpc must be \"2.0\".").toString())
		val asked = m.optJSONObject("params")?.optString("protocolVersion", "") ?: ""
		val version = if (asked in VERSIONS) asked else VERSIONS[0]
		val session = newSessionId()
		synchronized(sessions) {
			sessions += session
			while (sessions.size > maxSessions) sessions.remove(sessions.first())
		}
		val result =
			JSONObject()
				.put("protocolVersion", version)
				.put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
				.put("serverInfo", JSONObject().put("name", SERVER_NAME).put("title", "SQLite debugger").put("version", serverVersion))
				.put("instructions", INSTRUCTIONS)
		return Reply(200, ok(id, result).toString(), mapOf(SESSION_HEADER to session))
	}

	/** The answer to a message of a batch that is past its reply budget: not run; null for one that needs no answer. */
	private fun skipped(raw: Any?): JSONObject? {
		val m = raw as? JSONObject ?: return null
		if (m.opt("method") !is String || !m.has("id")) return null
		val id = m.opt("id")
		val detail = "This batch's replies already reached ${MAX_BATCH_REPLY_CHARS / (1024 * 1024)} MiB: send the rest in another POST."
		return error(if (validId(id)) id else null, BATCH_BUDGET_EXCEEDED, detail)
	}

	/** One message of a POST; null when it needs no answer. */
	private fun handle(
		raw: Any?,
		deadline: Long?,
	): JSONObject? {
		val m = raw as? JSONObject ?: return error(null, INVALID_REQUEST, "Each message must be a JSON object.")
		val hasId = m.has("id")
		val id = m.opt("id")
		val method = m.opt("method") as? String
		if (method == null) {
			// A response to something this server asked: it never asks, so there's nothing to do.
			if (m.has("result") || m.has("error")) return null
			return error(if (validId(id)) id else null, INVALID_REQUEST, "A request needs a method.")
		}
		if (!hasId) return null // a notification, such as notifications/initialized: no answer
		if (!validId(id)) return error(null, INVALID_REQUEST, "id must be a string or a number.")
		if (m.optString("jsonrpc") != "2.0") return error(id, INVALID_REQUEST, "jsonrpc must be \"2.0\".")
		return when (method) {
			"ping" -> ok(id, JSONObject())
			"tools/list" -> ok(id, JSONObject().put("tools", JSONArray().also { a -> tools.forEach { a.put(it.describe()) } }))
			"tools/call" -> call(id, m.opt("params"), deadline)
			else -> error(id, METHOD_NOT_FOUND, "Unknown method \"$method\".")
		}
	}

	private fun call(
		id: Any,
		rawParams: Any?,
		deadline: Long?,
	): JSONObject {
		val params = rawParams as? JSONObject ?: return error(id, INVALID_PARAMS, "tools/call needs params, with the tool's name.")
		val name = params.opt("name") as? String ?: return error(id, INVALID_PARAMS, "tools/call needs the tool's name.")
		val tool = tools.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "Unknown tool \"$name\".")
		val args =
			when (val a = params.opt("arguments")) {
				null, JSONObject.NULL -> JSONObject()
				is JSONObject -> a
				else -> return error(id, INVALID_PARAMS, "arguments must be an object.")
			}
		val result =
			try {
				val out = tool.run(args, deadline)
				JSONObject().put("content", JSONArray().put(text(out.toString()))).put("structuredContent", out).put("isError", false)
			} catch (e: McpToolError) {
				JSONObject().put("content", JSONArray().put(text(e.message ?: "The tool failed."))).put("isError", true).also { r ->
					e.details?.let { r.put("structuredContent", it) }
				}
			} catch (e: Exception) {
				JSONObject()
					.put("content", JSONArray().put(text("The tool failed: ${e.message ?: e.javaClass.simpleName}")))
					.put("isError", true)
			}
		return ok(id, result)
	}

	private fun validId(id: Any?) = id is String || id is Number

	private fun text(t: String) = JSONObject().put("type", "text").put("text", t)

	private fun ok(
		id: Any,
		result: JSONObject,
	) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

	private fun error(
		id: Any?,
		code: Int,
		message: String,
	) = JSONObject()
		.put("jsonrpc", "2.0")
		.put("id", id ?: JSONObject.NULL)
		.put("error", JSONObject().put("code", code).put("message", message))
}
