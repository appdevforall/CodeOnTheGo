package org.appdevforall.cotg.quickbuild.daemon.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Encodes/decodes the line-delimited JSON protocol. Pure functions over strings - no IO -
 * so malformed-input handling is exhaustively unit-testable. Gson escapes newlines inside
 * strings, so an encoded response is always exactly one line.
 */
object ProtocolCodec {
	/** Parses one request line. Never throws: broken input becomes [ParseResult.Malformed]. */
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
			root.longOrNull("id") ?: return ParseResult.Malformed(
				ParseResult.Malformed.UNKNOWN_ID,
				"missing or non-numeric 'id'",
			)

		return try {
			when (val op = root.stringOrNull("op")) {
				"configure" ->
					ParseResult.Parsed(
						ConfigureRequest(
							id = id,
							projectRoot = root.requireString("projectRoot"),
							classpath = root.requireStringList("classpath"),
							outDir = root.requireString("outDir"),
							aapt2 = root.requireString("aapt2"),
							d8Jar = root.requireString("d8Jar"),
							androidJar = root.requireString("androidJar"),
							minApi = root.longOrNull("minApi")?.toInt() ?: ConfigureRequest.DEFAULT_MIN_API,
							compilerPlugins = root.optionalStringList("compilerPlugins"),
						),
					)
				"compile" ->
					ParseResult.Parsed(
						CompileRequest(
							id = id,
							allSources = root.requireStringList("allSources"),
							changedFiles = root.requireStringList("changedFiles"),
						),
					)
				"dex" ->
					ParseResult.Parsed(
						DexRequest(id = id, classesDirs = root.requireStringList("classesDirs")),
					)
				"relink" ->
					ParseResult.Parsed(
						RelinkRequest(
							id = id,
							resDirs = root.requireStringList("resDirs"),
							manifest = root.requireString("manifest"),
						),
					)
				"ping" -> ParseResult.Parsed(PingRequest(id))
				"shutdown" -> ParseResult.Parsed(ShutdownRequest(id))
				null -> ParseResult.Malformed(id, "missing 'op'")
				else -> ParseResult.Malformed(id, "unknown op '$op'")
			}
		} catch (e: MissingFieldException) {
			ParseResult.Malformed(id, e.message ?: "malformed request")
		}
	}

	/** Encodes a response as one JSON line (no trailing newline). */
	fun encode(response: DaemonResponse): String {
		val root = JsonObject()
		root.addProperty("id", response.id)
		root.addProperty("ok", response.ok)
		for ((key, value) in response.values) {
			when (value) {
				is Number -> root.addProperty(key, value)
				is Boolean -> root.addProperty(key, value)
				else -> root.addProperty(key, value.toString())
			}
		}
		if (response.diagnostics.isNotEmpty()) {
			val array = JsonArray()
			for (diagnostic in response.diagnostics) {
				val obj = JsonObject()
				obj.addProperty("severity", diagnostic.severity.name)
				obj.addProperty("message", diagnostic.message)
				diagnostic.file?.let { obj.addProperty("file", it) }
				diagnostic.line?.let { obj.addProperty("line", it) }
				diagnostic.column?.let { obj.addProperty("column", it) }
				array.add(obj)
			}
			root.add("diagnostics", array)
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
