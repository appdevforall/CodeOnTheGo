package com.itsaky.androidide.plugins.manager.services.sqltemplate

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.itsaky.androidide.plugins.services.RoomSchema
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Finds the SQL templates and Room's exported schemas in a project (ADFA-7302). Plain JVM, unit-tested without Android. */
internal object ProjectSqlFiles {
	const val TEMPLATE_SUFFIX = ".sql.peb"

	/** Folders below this depth are not searched: schemas sit about four levels down, templates wherever the user puts them. */
	const val MAX_DEPTH = 12

	/**
	 * At most this many entries are returned, so a project full of JSON cannot make a call slow: the first ones in the
	 * documented order (templates by path; schemas by class, then version), the rest left out.
	 */
	const val MAX_MATCHES = 2000

	/** Room's schemas nest about six levels; a file nested deeper than this is not read as one. */
	const val MAX_JSON_DEPTH = 64

	/** The walk stops collecting candidates here, so the cut above is made on a sorted list, but a pathological project cannot fill memory. */
	const val MAX_CANDIDATES = 50_000

	/** A template or schema file larger than this is not read. */
	const val MAX_FILE_BYTES = 8L * 1024 * 1024

	/** Build output holds copies of the schemas (and of nothing a user edits); hidden folders hold Git, Gradle and IDE state. */
	private val SKIPPED = setOf("build", "node_modules")

	private val VERSION_FILE = Regex("(\\d{1,9})\\.json")
	private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

	/** The project's `*.sql.peb` files, relative to [root], sorted. */
	fun templates(root: File): List<String> =
		walk(root) { it.name.endsWith(TEMPLATE_SUFFIX) }.map { relative(root, it) }.sorted().take(MAX_MATCHES)

	/**
	 * Every schema Room exported under [root], by class and version, at most [MAX_MATCHES] (the first in that order).
	 * A file that is not Room's, is unreadable or cannot be parsed (JSON nested too deeply, say) is skipped.
	 */
	fun roomSchemas(root: File): List<RoomSchema> =
		walk(root) { VERSION_FILE.matches(it.name) && CLASS_NAME.matches(it.parentFile?.name ?: "") }
			.sortedWith(compareBy<File>({ it.parentFile!!.name }, { versionOf(it) }, { relative(root, it) }))
			.take(MAX_MATCHES)
			.mapNotNull { file ->
				try {
					parseRoomSchema(readBounded(file), file.parentFile!!.name, relative(root, file))
				} catch (e: Exception) {
					null
				} catch (e: StackOverflowError) {
					// Backstop for the depth limit below, as toValue recurses once per level of nesting.
					null
				}
			}

	private fun versionOf(file: File) = VERSION_FILE.matchEntire(file.name)!!.groupValues[1].toInt()

	/** [file] is over [MAX_FILE_BYTES]. */
	class TooLarge : IOException("larger than ${MAX_FILE_BYTES / (1024 * 1024)} MiB")

	/**
	 * [file]'s text (UTF-8), if it is a regular file (not a link, FIFO or device, which could block the read) of at most
	 * [MAX_FILE_BYTES]. The size is not taken from the file system: at most that many bytes plus one are read.
	 *
	 * @throws TooLarge for a longer file
	 * @throws IOException for anything else that stops the read
	 */
	@Throws(IOException::class)
	fun readBounded(file: File): String {
		val path = file.toPath()
		val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
		if (!attributes.isRegularFile) throw IOException("not a regular file")
		Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
			// Not readNBytes: it needs API 33 (minSdk is 28), and desugaring does not backport it for this package.
			val out = ByteArrayOutputStream()
			val buffer = ByteArray(64 * 1024)
			while (true) {
				// At least 1, as a longer file has already thrown: at most MAX_FILE_BYTES + 1 bytes are read.
				val room = minOf(buffer.size.toLong(), MAX_FILE_BYTES + 1 - out.size()).toInt()
				val n = input.read(buffer, 0, room)
				if (n < 0) break
				out.write(buffer, 0, n)
				if (out.size() > MAX_FILE_BYTES) throw TooLarge()
			}
			return String(out.toByteArray(), Charsets.UTF_8)
		}
	}

	/**
	 * A Room schema file, or null if it is not one (or is nested deeper than [MAX_JSON_DEPTH]): Room writes `formatVersion` and a `database` with `version` and
	 * `identityHash`. Room leaves out `indices`, `foreignKeys` and `views` when they are empty; they come back empty.
	 */
	fun parseRoomSchema(
		text: String,
		className: String,
		path: String,
	): RoomSchema? =
		try {
			parse(text, className, path)
		} catch (e: TooDeep) {
			null
		}

	private class TooDeep : RuntimeException("JSON nested more than $MAX_JSON_DEPTH levels")

	private fun parse(
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
	private fun toValue(
		e: JsonElement?,
		depth: Int = 0,
	): Any? =
		when {
			depth > MAX_JSON_DEPTH -> {
				throw TooDeep()
			}

			e == null || e.isJsonNull -> {
				null
			}

			e is JsonObject -> {
				val map = LinkedHashMap<String, Any?>()
				for ((k, v) in e.entrySet()) map[k] = toValue(v, depth + 1)
				java.util.Collections.unmodifiableMap(map)
			}

			e is JsonArray -> {
				java.util.Collections.unmodifiableList(e.map { toValue(it, depth + 1) })
			}

			e is JsonPrimitive && e.isBoolean -> {
				e.asBoolean
			}

			e is JsonPrimitive && e.isNumber -> {
				e.asBigDecimal.let { n -> runCatching { n.longValueExact() }.getOrElse { n.toDouble() } }
			}

			else -> {
				e.asString
			}
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
		while (stack.isNotEmpty() && found.size < MAX_CANDIDATES) {
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
					if (found.size >= MAX_CANDIDATES) break
				}
			}
		}
		return found
	}

	private fun isLink(file: File) =
		runCatching {
			Files.isSymbolicLink(file.toPath())
		}.getOrDefault(true)

	fun relative(
		root: File,
		file: File,
	): String = file.relativeTo(root).invariantSeparatorsPath
}
