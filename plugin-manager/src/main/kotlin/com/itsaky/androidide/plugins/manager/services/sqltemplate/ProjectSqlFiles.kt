package com.itsaky.androidide.plugins.manager.services.sqltemplate

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.itsaky.androidide.plugins.services.RoomSchema
import java.io.File

/** Finds the SQL templates and Room's exported schemas in a project (ADFA-7302). Plain JVM, unit-tested without Android. */
internal object ProjectSqlFiles {
	const val TEMPLATE_SUFFIX = ".sql.peb"

	/** Folders below this depth are not searched: schemas sit about four levels down, templates wherever the user puts them. */
	const val MAX_DEPTH = 12

	/** At most this many matches are returned, so a project full of JSON cannot make a call slow. */
	const val MAX_MATCHES = 2000

	/** A template or schema file larger than this is not read. */
	const val MAX_FILE_BYTES = 8L * 1024 * 1024

	/** Build output holds copies of the schemas (and of nothing a user edits); hidden folders hold Git, Gradle and IDE state. */
	private val SKIPPED = setOf("build", "node_modules")

	private val VERSION_FILE = Regex("(\\d{1,9})\\.json")
	private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

	/** The project's `*.sql.peb` files, relative to [root], sorted. */
	fun templates(root: File): List<String> = walk(root) { it.name.endsWith(TEMPLATE_SUFFIX) }.map { relative(root, it) }.sorted()

	/** Every schema Room exported under [root], by class and version. A file that is not Room's, or unreadable, is skipped. */
	fun roomSchemas(root: File): List<RoomSchema> =
		walk(root) { VERSION_FILE.matches(it.name) && CLASS_NAME.matches(it.parentFile?.name ?: "") }
			.mapNotNull { file ->
				if (file.length() > MAX_FILE_BYTES) return@mapNotNull null
				val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
				parseRoomSchema(text, file.parentFile!!.name, relative(root, file))
			}.sortedWith(compareBy({ it.className }, { it.version }))

	/**
	 * A Room schema file, or null if it is not one: Room writes `formatVersion` and a `database` with `version` and
	 * `identityHash`. Room leaves out `indices`, `foreignKeys` and `views` when they are empty; they come back empty.
	 */
	fun parseRoomSchema(
		text: String,
		className: String,
		path: String,
	): RoomSchema? {
		val json = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return null
		if (!json.has("formatVersion")) return null
		val database = json.get("database") as? JsonObject ?: return null
		val version = (database.get("version") as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt ?: return null
		val hash = (database.get("identityHash") as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: return null

		@Suppress("UNCHECKED_CAST")
		val entities =
			((toValue(database.get("entities")) as? List<Any?>) ?: emptyList())
				.filterIsInstance<Map<String, Any?>>()
				.map { entity ->
					val e = LinkedHashMap(entity)
					e.putIfAbsent("indices", emptyList<Any?>())
					e.putIfAbsent("foreignKeys", emptyList<Any?>())
					java.util.Collections.unmodifiableMap(e) as Map<String, Any>
				}

		@Suppress("UNCHECKED_CAST")
		val views =
			((toValue(database.get("views")) as? List<Any?>) ?: emptyList())
				.filterIsInstance<Map<String, Any>>()
		return RoomSchema(className, version, hash, path, entities, views)
	}

	/** JSON as Kotlin values: maps, lists, String, Boolean, Long for a whole number, Double otherwise; all unmodifiable. */
	private fun toValue(e: JsonElement?): Any? =
		when {
			e == null || e.isJsonNull -> null
			e is JsonObject -> {
				val map = LinkedHashMap<String, Any?>()
				for ((k, v) in e.entrySet()) map[k] = toValue(v)
				java.util.Collections.unmodifiableMap(map)
			}
			e is JsonArray -> java.util.Collections.unmodifiableList(e.map { toValue(it) })
			e is JsonPrimitive && e.isBoolean -> e.asBoolean
			e is JsonPrimitive && e.isNumber -> e.asBigDecimal.let { n -> runCatching { n.longValueExact() }.getOrElse { n.toDouble() } }
			else -> e.asString
		}

	/**
	 * Files under [root] that [accept] takes, depth first, skipping build output and hidden folders. Checks for an
	 * interrupt between folders, so a cancelled call stops.
	 */
	fun walk(
		root: File,
		accept: (File) -> Boolean,
	): List<File> {
		val found = ArrayList<File>()
		val stack = ArrayDeque<Pair<File, Int>>()
		stack.addLast(root to 0)
		while (stack.isNotEmpty() && found.size < MAX_MATCHES) {
			if (Thread.interrupted()) throw InterruptedException()
			val (dir, depth) = stack.removeLast()
			val children = dir.listFiles() ?: continue
			for (child in children.sortedBy { it.name }) {
				if (child.name.startsWith(".")) continue
				// A link could lead out of the project or back into it: only real folders are entered.
				if (child.isDirectory) {
					if (depth < MAX_DEPTH && child.name !in SKIPPED && !isLink(child)) stack.addLast(child to depth + 1)
				} else if (child.isFile && accept(child)) {
					found += child
					if (found.size >= MAX_MATCHES) break
				}
			}
		}
		return found
	}

	private fun isLink(file: File) = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)

	fun relative(
		root: File,
		file: File,
	): String = file.relativeTo(root).invariantSeparatorsPath
}
