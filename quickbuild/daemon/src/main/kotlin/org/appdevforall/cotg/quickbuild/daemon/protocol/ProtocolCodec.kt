package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.appdevforall.cotg.quickbuild.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonOps
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.protocol.ParseResult
import org.appdevforall.cotg.quickbuild.protocol.PingRequest
import org.appdevforall.cotg.quickbuild.protocol.RelinkRequest
import org.appdevforall.cotg.quickbuild.protocol.RequestKeys
import org.appdevforall.cotg.quickbuild.protocol.ResponseKeys
import org.appdevforall.cotg.quickbuild.protocol.ShutdownRequest

/**
 * Encodes and decodes the line-delimited JSON protocol. Pure functions over strings, no IO, so
 * malformed-input handling is exhaustively unit-testable. Gson escapes newlines inside strings,
 * so an encoded response is always exactly one line.
 */
object ProtocolCodec {
	/**
	 * Parses one request line. Never throws: broken input becomes [ParseResult.Malformed].
	 *
	 * @param line exactly one JSON object, without its trailing newline; blank lines are the
	 *   caller's to skip.
	 * @return [ParseResult.Parsed] with the typed request, or [ParseResult.Malformed] carrying
	 *   the id when one could be read and [ParseResult.Malformed.UNKNOWN_ID] when it could not.
	 */
	fun parse(line: String): ParseResult {
		val root =
			try {
				val element = JsonParser.parseString(line)
				if (!element.isJsonObject) {
					return ParseResult.Malformed(ParseResult.Malformed.UNKNOWN_ID, "request is not a JSON object")
				}
				element.asJsonObject
			} catch (e: Exception) {
				return ParseResult.Malformed(ParseResult.Malformed.UNKNOWN_ID, "invalid JSON: ${e.message}")
			}

		val id =
			root.longOrNull(RequestKeys.ID) ?: return ParseResult.Malformed(
				ParseResult.Malformed.UNKNOWN_ID,
				"missing or non-numeric 'id'",
			)

		return try {
			when (val op = root.stringOrNull(RequestKeys.OP)) {
				DaemonOps.CONFIGURE -> {
					ParseResult.Parsed(
						ConfigureRequest(
							id = id,
							projectRoot = root.requireString(RequestKeys.PROJECT_ROOT),
							classpath = root.requireStringList(RequestKeys.CLASSPATH),
							outDir = root.requireString(RequestKeys.OUT_DIR),
							aapt2 = root.stringOrNull(RequestKeys.AAPT2),
							d8Jar = root.stringOrNull(RequestKeys.D8_JAR),
							androidJar = root.stringOrNull(RequestKeys.ANDROID_JAR),
							minApi = root.longOrNull(RequestKeys.MIN_API)?.toInt() ?: ConfigureRequest.DEFAULT_MIN_API,
							compilerPlugins = root.optionalStringList(RequestKeys.COMPILER_PLUGINS),
						),
					)
				}

				DaemonOps.COMPILE -> {
					ParseResult.Parsed(
						CompileRequest(
							id = id,
							allSources = root.requireStringList(RequestKeys.ALL_SOURCES),
							changedFiles = root.requireStringList(RequestKeys.CHANGED_FILES),
							removedFiles = root.optionalStringList(RequestKeys.REMOVED_FILES),
						),
					)
				}

				DaemonOps.DEX -> {
					ParseResult.Parsed(
						DexRequest(id = id, classesDirs = root.requireStringList(RequestKeys.CLASSES_DIRS)),
					)
				}

				DaemonOps.RELINK -> {
					ParseResult.Parsed(
						RelinkRequest(
							id = id,
							resDirs = root.requireStringList(RequestKeys.RES_DIRS),
							manifest = root.requireString(RequestKeys.MANIFEST),
							stableIds = root.stringOrNull(RequestKeys.STABLE_IDS),
							libraryResources = root.optionalStringList(RequestKeys.LIBRARY_RESOURCES),
						),
					)
				}

				DaemonOps.PING -> {
					ParseResult.Parsed(PingRequest(id))
				}

				DaemonOps.SHUTDOWN -> {
					ParseResult.Parsed(ShutdownRequest(id))
				}

				null -> {
					ParseResult.Malformed(id, "missing 'op'")
				}

				else -> {
					ParseResult.Malformed(id, "unknown op '$op'")
				}
			}
		} catch (e: MissingFieldException) {
			ParseResult.Malformed(id, e.message ?: "malformed request")
		}
	}

	/**
	 * Encodes a response as one JSON line (no trailing newline).
	 *
	 * @param response its `values` may hold numbers, booleans, collections of strings, or
	 *   anything else, which is written as its `toString`.
	 * @return a single line - Gson escapes any newline inside a string - that the caller must
	 *   terminate itself.
	 */
	fun encode(response: DaemonResponse): String {
		val root = JsonObject()
		root.addProperty(ResponseKeys.ID, response.id)
		root.addProperty(ResponseKeys.OK, response.ok)
		for ((key, value) in response.values) {
			when (value) {
				is Number -> {
					root.addProperty(key, value)
				}

				is Boolean -> {
					root.addProperty(key, value)
				}

				is Collection<*> -> {
					val array = JsonArray()
					value.forEach { array.add(it.toString()) }
					root.add(key, array)
				}

				else -> {
					root.addProperty(key, value.toString())
				}
			}
		}
		if (response.diagnostics.isNotEmpty()) {
			val array = JsonArray()
			for (diagnostic in response.diagnostics) {
				val obj = JsonObject()
				obj.addProperty(ResponseKeys.Diagnostics.SEVERITY, diagnostic.severity.name)
				obj.addProperty(ResponseKeys.Diagnostics.MESSAGE, diagnostic.message)
				diagnostic.file?.let { obj.addProperty(ResponseKeys.Diagnostics.FILE, it) }
				diagnostic.line?.let { obj.addProperty(ResponseKeys.Diagnostics.LINE, it) }
				diagnostic.column?.let { obj.addProperty(ResponseKeys.Diagnostics.COLUMN, it) }
				array.add(obj)
			}
			root.add(ResponseKeys.DIAGNOSTICS, array)
		}
		return root.toString()
	}

	private class MissingFieldException(
		message: String,
	) : Exception(message)

	private fun JsonObject.longOrNull(name: String): Long? {
		val element = get(name) ?: return null
		val primitive = element as? JsonPrimitive ?: return null
		if (!primitive.isNumber) return null
		return primitive.asLong
	}

	private fun JsonObject.stringOrNull(name: String): String? {
		val element = get(name) ?: return null
		val primitive = element as? JsonPrimitive ?: return null
		if (!primitive.isString) return null
		return primitive.asString
	}

	private fun JsonObject.requireString(name: String): String =
		stringOrNull(name) ?: throw MissingFieldException("missing or non-string '$name'")

	private fun JsonObject.optionalStringList(name: String): List<String> = if (has(name)) requireStringList(name) else emptyList()

	private fun JsonObject.requireStringList(name: String): List<String> {
		val element = get(name) ?: throw MissingFieldException("missing '$name'")
		if (!element.isJsonArray) throw MissingFieldException("'$name' is not an array")
		return element.asJsonArray.map { item ->
			val primitive = item as? JsonPrimitive
			if (primitive == null || !primitive.isString) {
				throw MissingFieldException("'$name' contains a non-string element")
			}
			primitive.asString
		}
	}
}
