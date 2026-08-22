/*
 *  This file is part of Code on the Go.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.documentation

import android.database.sqlite.SQLiteDatabase
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.utils.DatabaseVersionResolver
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import io.pebbletemplates.pebble.template.PebbleTemplate
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.StringWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Copies [bytes] into a direct [ByteBuffer] -- brotli4j's `attachDictionary` requires a direct
 * buffer, a heap-backed one throws `IllegalArgumentException`.
 *
 * The capacity must be exactly [bytes]`.size`: `attachDictionary` reads the whole capacity and
 * ignores position/limit, so trailing slack from an over-allocated buffer is treated as dictionary
 * content and every decode then fails with `IOException: corrupted input`.
 */
fun toDirectByteBuffer(bytes: ByteArray): ByteBuffer =
	ByteBuffer.allocateDirect(bytes.size).apply {
		put(bytes)
		flip()
	}

/**
 * Reads [chunks] back to back as one stream, without concatenating them into a new array.
 * Cheap to build twice, which the no-dictionary retry in [DocumentationContentSource] relies on.
 */
fun chunksAsStream(chunks: List<ByteArray>): InputStream =
	SequenceInputStream(Collections.enumeration(chunks.map { ByteArrayInputStream(it) }))

/**
 * Joins [chunks] into one exactly-sized array. A ByteArrayOutputStream would repeatedly double its
 * buffer and then hand back a second full copy -- avoidable here since the total is known up front.
 * Returns the sole element as-is when there is nothing to join.
 */
fun joinChunks(chunks: List<ByteArray>): ByteArray {
	if (chunks.size == 1) {
		return chunks[0]
	}
	val joined = ByteArray(chunks.sumOf { it.size })
	var offset = 0
	for (chunk in chunks) {
		chunk.copyInto(joined, offset)
		offset += chunk.size
	}
	return joined
}

/** One row of documentation content, decoded and rendered, ready to send. */
data class DocumentationContent(
	val bytes: ByteArray,
	val mimeType: String,
) {
	// Data class equality over a ByteArray would compare identity, which is never what a caller
	// means; content equality on a multi-megabyte blob is not what it wants either.
	override fun equals(other: Any?): Boolean = this === other

	override fun hashCode(): Int = System.identityHashCode(this)
}

/** What a [DocumentationContentSource.lookup] found for a path. */
sealed interface DocumentationLookup {
	data class Found(
		val content: DocumentationContent,
	) : DocumentationLookup

	object NotFound : DocumentationLookup

	/** The path matched more than one row, which means the database is corrupt. */
	data class Ambiguous(
		val rowCount: Int,
	) : DocumentationLookup

	/** The read itself failed. */
	data class Failed(
		val cause: Exception,
	) : DocumentationLookup
}

/**
 * Reads documentation content out of `documentation.db`: the row lookup, reassembly of chunked
 * rows, the shared-dictionary Brotli decode (ADFA-5153), and the swap to a newer database dropped
 * on the sdcard.
 *
 * One pipeline with two callers (ADFA-5176): `WebServer`, which wraps it in HTTP, and
 * [DocumentationRequestInterceptor], which answers a WebView in-process with no socket at all. A row
 * that is a Pebble template context is rendered here too, so both transports serve a finished page
 * and neither needs the template engine itself.
 *
 * Thread-safe: [lookup] and [withDatabase] hold a read lock for the whole read, and the swap takes
 * the write lock, since swapping closes the handle a reader could be using. Readers never block
 * each other.
 */
class DocumentationContentSource(
	private val databaseFile: File,
	private val debugDatabaseFile: File,
	debugCheckIntervalMs: Long = 1_000,
) : Closeable {
	private val log = LoggerFactory.getLogger(DocumentationContentSource::class.java)

	private val databaseLock = ReentrantReadWriteLock()

	// Read without the lock in the open-on-demand check, hence volatile.
	@Volatile
	private var database: SQLiteDatabase? = null

	@Volatile
	private var databaseTimestamp: Long = -1

	/**
	 * Bumped on every swap, so a caller can tell that anything it cached from this source --
	 * a compiled template, a looked-up template id -- belongs to a database that is gone.
	 */
	@Volatile
	var generation: Long = 0
		private set

	// A debug database whose swap already failed, so a corrupt or unreadable one is not reopened
	// on every check. A newer copy has a different timestamp and is retried, which is the case
	// that matters: replacing the file is exactly how a developer fixes it.
	private var failedDebugSwapTimestamp: Long = -1

	// The dictionary the Content rows are compressed against. Loaded on the first decode that
	// needs it after a swap rather than eagerly, and then cached for that database. Null when the
	// active database predates the dictionary migration -- CompressionDictionary won't exist.
	private var compressionDictionary: ByteBuffer? = null
	private var compressionDictionaryStale = true

	private val pebbleEngine = PebbleEngine.Builder().loader(StringLoader()).build()

	// Compiled templates for the active database, cleared when it is swapped.
	private val templateCache = ConcurrentHashMap<Int, PebbleTemplate>()

	private val gson: Gson =
		GsonBuilder()
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.create()

	private val templateContextType = object : TypeToken<Map<String, Any>>() {}.type

	private val debugCheckIntervalNanos = TimeUnit.MILLISECONDS.toNanos(debugCheckIntervalMs)

	// One interval in the past, so the first lookup still checks for a debug database.
	private val lastDebugCheckNanos = AtomicLong(System.nanoTime() - debugCheckIntervalNanos)

	/**
	 * Opens the installed database. Callers that need to fail loudly (the web server, which should
	 * not bind a port it cannot serve) call this; the rest let [lookup] open it on demand.
	 */
	fun open() {
		databaseLock.write {
			if (database != null) return@write
			switchToDatabase(databaseFile.absolutePath, timestampOf(databaseFile))
		}
	}

	/** The row for [path], decoded but not rendered. */
	fun lookup(path: String): DocumentationLookup {
		// Both of these take the write lock when they act, so they run before the read lock below:
		// a ReentrantReadWriteLock does not upgrade.
		if (!openIfNeeded()) return DocumentationLookup.NotFound
		swapDebugDatabaseIfNewer()

		return databaseLock.read {
			val database = database ?: return@read DocumentationLookup.NotFound

			try {
				readContent(database, path)
			} catch (e: Exception) {
				log.error("Cannot read '{}': {}", path, e.message)
				DocumentationLookup.Failed(e)
			}
		}
	}

	/**
	 * Runs [block] against the active database with the read lock held, for the queries this class
	 * does not own -- the bookshelf join, the template lookup, the developer table dumps.
	 */
	fun <T> withDatabase(block: (SQLiteDatabase) -> T): T {
		openIfNeeded()
		swapDebugDatabaseIfNewer()

		return databaseLock.read {
			val database = checkNotNull(database) { "documentation database '$databaseFile' is not open" }
			block(database)
		}
	}

	/**
	 * Renders [contextJson] through the template [templateId] -- for a caller that has the context
	 * and the template id in hand, rather than a path to look up. [path] is for diagnostics only.
	 */
	fun renderTemplate(
		templateId: Int,
		contextJson: ByteArray,
		path: String,
	): ByteArray = withDatabase { database -> render(database, templateId, contextJson, path) }

	/** Drops the compiled templates, for the developer sentinel that forces a re-render. */
	fun clearTemplateCache() {
		templateCache.clear()
	}

	/** The last-modified time of [pathname], or -1 when it does not exist. */
	fun timestampOf(
		file: File,
		silent: Boolean = true,
	): Long {
		if (!file.exists()) return -1

		val timestamp = file.lastModified()
		if (!silent) {
			val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
			log.debug("{} was last modified at {}.", file, format.format(Date(timestamp)))
		}

		return timestamp
	}

	override fun close() {
		databaseLock.write {
			try {
				database?.close()
			} catch (e: Exception) {
				log.error("Cannot close the documentation database: {}", e.message)
			}
			database = null
		}
	}

	/** True once the database is open. Failure is logged, not thrown: a caller falls back instead. */
	private fun openIfNeeded(): Boolean {
		if (database != null) return true

		return try {
			open()
			database != null
		} catch (e: Exception) {
			log.error("Cannot open the documentation database '{}': {}", databaseFile, e.message)
			false
		}
	}

	private fun readContent(
		database: SQLiteDatabase,
		path: String,
	): DocumentationLookup {
		// Primed before the row is read, not inside decompressBrotli, so a database's dictionary is
		// loaded on its first content fetch (ADFA-5153's contract) rather than on the first fetch
		// that happens to be Brotli-compressed. Still at most once per database.
		compressionDictionary(database)

		database.rawQuery(CONTENT_QUERY, arrayOf(path)).use { cursor ->
			if (cursor.count == 0) return DocumentationLookup.NotFound
			if (cursor.count != 1) return DocumentationLookup.Ambiguous(cursor.count)

			cursor.moveToFirst()
			val firstChunk = cursor.getBlob(0)
			val mimeType = cursor.getString(1)
			val compression = cursor.getString(2)
			val templateId = cursor.getInt(3)

			val chunks = readChunks(database, path, firstChunk)
			val decoded = if (compression == "brotli") decompressBrotli(database, chunks) else joinChunks(chunks)
			val bytes = if (templateId > 0) render(database, templateId, decoded, path) else decoded

			return DocumentationLookup.Found(DocumentationContent(bytes, mimeType))
		}
	}

	/**
	 * Renders one template: [contextJson] is the row's JSON, [templateId] names the template row.
	 * Compiled templates are cached per database, so a repeat visit re-renders without recompiling.
	 */
	private fun render(
		database: SQLiteDatabase,
		templateId: Int,
		contextJson: ByteArray,
		path: String,
	): ByteArray {
		val template =
			templateCache.getOrPut(templateId) {
				if (log.isDebugEnabled) log.debug("Template cache miss for id {}, path '{}'.", templateId, path)
				compileTemplate(database, templateId, path)
			}

		val contextString = contextJson.toString(Charsets.UTF_8)
		if (contextString.isBlank() || contextString.trim() == "null") {
			throw IllegalStateException("Template ID $templateId has empty or null JSON context")
		}
		val context: Map<String, Any> = gson.fromJson(contextString, templateContextType)

		return StringWriter().also { template.evaluate(it, context) }.toString().toByteArray()
	}

	private fun compileTemplate(
		database: SQLiteDatabase,
		templateId: Int,
		path: String,
	): PebbleTemplate =
		database.rawQuery("SELECT content FROM Templates WHERE id = ?", arrayOf(templateId.toString())).use { cursor ->
			when {
				cursor.count > 1 -> {
					throw IllegalStateException("Template ID $templateId is shared by more than one template")
				}

				!cursor.moveToFirst() -> {
					throw IllegalStateException("Template ID $templateId not found in the database, for path '$path'")
				}

				else -> {
					val body = cursor.getBlob(0)
					if (log.isDebugEnabled) log.debug("Compiling template {}, {} bytes.", templateId, body.size)
					pebbleEngine.getTemplate(body.toString(Charsets.UTF_8))
				}
			}
		}

	/**
	 * Content over [CONTENT_CHUNK_SIZE] is split across rows named `path-1`, `path-2`, ... The
	 * chunks stay a list rather than being concatenated: accumulating into a
	 * ByteArrayOutputStream held its doubling buffer *and* the copy from toByteArray() live
	 * alongside the decompressed output, roughly 35 MB transient for the largest bundled PDF.
	 */
	private fun readChunks(
		database: SQLiteDatabase,
		path: String,
		firstChunk: ByteArray,
	): List<ByteArray> {
		val chunks = mutableListOf(firstChunk)
		if (firstChunk.size != CONTENT_CHUNK_SIZE) return chunks

		var chunkNumber = 1
		var chunk = firstChunk
		while (chunk.size == CONTENT_CHUNK_SIZE) {
			database.rawQuery(CHUNK_QUERY, arrayOf("$path-$chunkNumber")).use { cursor ->
				if (!cursor.moveToFirst()) return chunks
				chunk = cursor.getBlob(0)
				chunks.add(chunk)
				chunkNumber++
			}
		}

		return chunks
	}

	/**
	 * Decompresses one Brotli row. Tries the shared dictionary first, since every migrated row
	 * requires it, then falls back to a plain decode for rows that were never dictionary
	 * compressed: plugin-contributed Tier 3 docs (PluginDocumentationManager/BrotliCompressor
	 * compress with no dictionary) or any row from a pre-migration database. Attaching a dictionary
	 * to a stream that was not compressed against one reliably fails rather than silently producing
	 * wrong bytes (verified empirically -- see docs/documentation-database.md), so this ordering
	 * never lets a dictionary-compressed row fall through to the plain path by accident.
	 */
	private fun decompressBrotli(
		database: SQLiteDatabase,
		chunks: List<ByteArray>,
	): ByteArray {
		val dictionary = compressionDictionary(database)
		if (dictionary != null) {
			try {
				return BrotliInputStream(chunksAsStream(chunks)).use { stream ->
					stream.attachDictionary(dictionary)
					stream.readBytes()
				}
			} catch (e: IOException) {
				log.debug(
					"Dictionary decode failed for a brotli row (likely dictionary-free plugin content); retrying without a dictionary: {}",
					e.message,
				)
			}
		}

		return BrotliInputStream(chunksAsStream(chunks)).use { it.readBytes() }
	}

	/**
	 * The active database's shared dictionary, loaded at most once per database. Synchronized rather
	 * than volatile-checked: two threads loading a 256 KB direct buffer in parallel is worth
	 * avoiding, and the read lock a caller already holds does not exclude them.
	 *
	 * The stale flag is cleared only after a *clean* load -- a dictionary, or a definitive absence.
	 * An unexpected failure propagates and leaves the flag set, so the next request retries instead
	 * of caching a transient error as "this database has no dictionary" for the rest of its life
	 * (ADFA-5153 review). The caller turns that into one failed request, not a permanent downgrade.
	 */
	private fun compressionDictionary(database: SQLiteDatabase): ByteBuffer? =
		synchronized(this) {
			if (compressionDictionaryStale) {
				compressionDictionary = dictionaryBytes(database)?.let { toDirectByteBuffer(it) }
				compressionDictionaryStale = false
			}
			compressionDictionary
		}

	/**
	 * The dictionary blob, or null -- logged -- when this database *definitively* has none: it
	 * declares a documentation version below
	 * [DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY], or its
	 * `CompressionDictionary` row is missing, null or empty. Any other failure is left to propagate,
	 * deliberately (see [compressionDictionary]).
	 *
	 * The gate is the declared version rather than the table's presence, matching `WebServer` --
	 * table sniffing infers a whole content format from one table existing, and gets it wrong in
	 * both directions (ADFA-5220). The table checks below still run, for a database that declares a
	 * new-enough version but has no usable row: without them the data query raises "no such table",
	 * which the caller treats as transient and would retry on every request.
	 */
	private fun dictionaryBytes(database: SQLiteDatabase): ByteArray? {
		val majorVersion = DatabaseVersionResolver.resolveMajorVersion(database)
		if (majorVersion == null ||
			majorVersion < DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY
		) {
			log.warn(
				"Database declares documentation version {}, below {}; decoding brotli content without a dictionary.",
				majorVersion ?: "none",
				DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY,
			)
			return null
		}

		val tableExists =
			database
				.rawQuery(
					"SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'CompressionDictionary'",
					null,
				).use { it.moveToFirst() }
		if (!tableExists) {
			log.warn("CompressionDictionary table not found; decoding brotli content without a dictionary.")
			return null
		}

		return database.rawQuery("SELECT data FROM CompressionDictionary WHERE id = 1", null).use { cursor ->
			if (!cursor.moveToFirst()) {
				log.warn("CompressionDictionary table is empty; decoding brotli content without a dictionary.")
				return null
			}

			val bytes = cursor.getBlob(0)
			when {
				bytes == null -> {
					log.warn("CompressionDictionary row has a NULL data column; decoding brotli content without a dictionary.")
					null
				}

				// An empty blob yields a 0-capacity buffer, which attachDictionary rejects -- every
				// decode would then fail with nothing above DEBUG to say why.
				bytes.isEmpty() -> {
					log.warn("CompressionDictionary row has an empty data column; decoding brotli content without a dictionary.")
					null
				}

				else -> {
					bytes
				}
			}
		}
	}

	/**
	 * Swaps to the sdcard debug database when a newer one has appeared. The stat behind this is
	 * rate-limited: the path is FUSE-backed emulated storage, and it is a developer-only override,
	 * so it used to cost a stat on every single request (ADFA-5175). Must not be called with the
	 * read lock held -- it takes the write lock, and this lock does not upgrade.
	 */
	private fun swapDebugDatabaseIfNewer() {
		val debugTimestamp = debugTimestampIfDue() ?: return
		if (debugTimestamp <= databaseTimestamp || debugTimestamp == failedDebugSwapTimestamp) return

		databaseLock.write {
			// Another thread may have swapped while this one waited for the lock.
			if (debugTimestamp <= databaseTimestamp || debugTimestamp == failedDebugSwapTimestamp) return@write

			try {
				switchToDatabase(debugDatabaseFile.absolutePath, debugTimestamp)
				failedDebugSwapTimestamp = -1
				log.info("Swapped to the debug database '{}'.", debugDatabaseFile)
			} catch (e: Exception) {
				failedDebugSwapTimestamp = debugTimestamp
				log.error(
					"Cannot swap to debug database '{}'; ignoring it until it changes: {}",
					debugDatabaseFile,
					e.message,
				)
			}
		}
	}

	/** The debug database's timestamp, or null when the last check was too recent. */
	private fun debugTimestampIfDue(): Long? {
		val now = System.nanoTime()
		val last = lastDebugCheckNanos.get()
		if (now - last < debugCheckIntervalNanos) return null
		if (!lastDebugCheckNanos.compareAndSet(last, now)) return null

		return timestampOf(debugDatabaseFile)
	}

	/**
	 * Opens [path] as the active database and refreshes everything that depends on which file is
	 * active, as one operation under the write lock. Opens the replacement before closing the old
	 * handle, so a failed open (this throws) leaves the previous database serving rather than
	 * leaving a closed handle behind.
	 */
	private fun switchToDatabase(
		path: String,
		timestamp: Long,
	) {
		val opened = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
		val previous = database

		database = opened
		databaseTimestamp = timestamp
		compressionDictionaryStale = true
		generation++

		try {
			previous?.close()
		} catch (e: Exception) {
			log.error("Cannot close previous database: {}", e.message)
		}
	}

	companion object {
		const val CONTENT_CHUNK_SIZE = 1024 * 1024

		private const val CONTENT_QUERY = """
			SELECT C.content, CT.value, CT.compression, C.templateId
			FROM   Content C, ContentTypes CT
			WHERE  C.contentTypeID = CT.id
			AND    C.path = ?
		"""

		private const val CHUNK_QUERY = "SELECT content FROM Content WHERE path = ? AND languageId = 1"
	}
}
