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
import com.aayushatharva.brotli4j.Brotli4jLoader
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
import java.net.URLDecoder
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

/**
 * One row of documentation content, decoded and rendered, ready to send.
 *
 * Deliberately not a `data class`. Generated equality over a [ByteArray] compares identity, which is
 * never what a caller means, and comparing multi-megabyte content is not what it wants either -- so
 * this used to be a data class with `equals`/`hashCode` overridden back to identity. That left
 * `copy()` behind, returning an object unequal to the one it was copied from. Nothing needs
 * value semantics here, so the class simply does not offer them.
 */
class DocumentationContent(
	val bytes: ByteArray,
	val mimeType: String,
)

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
 * What [DocumentationContentSource.lookupRequestPath] found, plus the path form that produced it --
 * so a transport reporting a miss or a corrupt row can quote the string that was actually queried.
 */
data class RequestLookup(
	val queriedPath: String,
	val lookup: DocumentationLookup,
)

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

	// Terminal: openIfNeeded() refuses once this is set, so a straggler call after close() -- a
	// shutdown-time log line, say -- cannot silently reopen a handle nothing will ever close.
	@Volatile
	private var closed = false

	@Volatile
	private var databaseTimestamp: Long = -1

	// Which file the active handle was opened on, so the rewrite check below knows whether the
	// installed file is the one being served.
	@Volatile
	private var activeDatabasePath: String? = null

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
	// Volatile because the check that reads it happens outside the write lock: without it a second
	// thread never sees the first's failure marker and re-attempts openDatabase on the broken file
	// while holding the write lock, serialising every reader behind a failing open -- the exact
	// behaviour this field exists to prevent. A 64-bit read is not atomic on armeabi-v7a either.
	@Volatile
	private var failedDebugSwapTimestamp: Long = -1

	// Same idea for the installed file: a reopen that failed -- typically because an installer is
	// still streaming into it -- is not retried until the file's timestamp moves again.
	@Volatile
	private var failedInstalledSwapTimestamp: Long = -1

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

	// One interval in the past, so the first lookup still checks for a changed database. The one
	// gate covers both stats: the sdcard debug file and the installed file's rewrite check.
	private val lastSwapCheckNanos = AtomicLong(System.nanoTime() - debugCheckIntervalNanos)

	/**
	 * Opens the installed database. Callers that need to fail loudly (the web server, which should
	 * not bind a port it cannot serve) call this; the rest let [lookup] open it on demand.
	 */
	fun open() {
		databaseLock.write {
			check(!closed) { "documentation content source for '$databaseFile' is closed" }
			if (database != null) return@write
			switchToDatabase(databaseFile.absolutePath, timestampOf(databaseFile))
		}
	}

	/** The row for [path], decoded but not rendered. */
	fun lookup(path: String): DocumentationLookup {
		// Both of these take the write lock when they act, so they run before the read lock below:
		// a ReentrantReadWriteLock does not upgrade.
		if (!openIfNeeded()) return DocumentationLookup.NotFound
		swapDatabaseIfChanged()

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
	 * The row for a request target as it appeared on the wire, with a percent-decoded fallback.
	 *
	 * Stored `Content.path` values in the shipped database are themselves percent-encoded -- pages
	 * reference their siblings with encoded targets (`src="Draft%20%20Tutorial_html_774738e5.png"`),
	 * and no row contains a literal space -- so [rawPath] is what matches and is tried first. The
	 * decoded form is tried on a miss, keeping a database ingested with decoded paths working.
	 *
	 * Both transports resolve paths through here (ADFA-5176): `WebServer` passes the raw request
	 * target and [DocumentationRequestInterceptor] passes `Uri.encodedPath`, so they cannot disagree
	 * about which pages exist -- the `nointercept` sentinel compares them on equal terms.
	 */
	fun lookupRequestPath(rawPath: String): RequestLookup {
		val raw = lookup(rawPath)
		if (raw !is DocumentationLookup.NotFound) return RequestLookup(rawPath, raw)

		val decodedPath = decodeRequestPath(rawPath)
		if (decodedPath == rawPath) return RequestLookup(rawPath, raw)

		return RequestLookup(decodedPath, lookup(decodedPath))
	}

	/**
	 * Percent-decodes [path]. `URLDecoder` is used rather than `Uri.decode` so this stays testable
	 * off-device, and `+` is protected first because `URLDecoder` -- alone among the two -- turns it
	 * into a space, which would break any stored path containing a literal plus.
	 */
	private fun decodeRequestPath(path: String): String =
		try {
			URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
		} catch (e: IllegalArgumentException) {
			// A malformed escape ("%zz") is not a reason to fail the request: the caller has already
			// looked the path up verbatim, so there is simply no fallback form left to try.
			log.warn("Cannot decode request path '{}', using it as-is: {}", path, e.message)
			path
		}

	/**
	 * Applies any pending debug-database swap, so a caller can decide whether its own per-database
	 * caches are stale *before* it reads.
	 *
	 * Without this, a caller that checks [generation] and then calls [lookup] or [withDatabase]
	 * checks the generation from before the swap those calls perform: on the request that swaps, it
	 * still believes its caches belong to the new database. Idempotent and throttled by the debug
	 * check interval, so calling it and then reading costs one extra timestamp comparison.
	 */
	fun refreshDatabase() {
		openIfNeeded()
		swapDatabaseIfChanged()
	}

	/**
	 * Runs [block] against the active database with the read lock held, for the queries this class
	 * does not own -- the bookshelf join, the template lookup, the developer table dumps.
	 */
	fun <T> withDatabase(block: (SQLiteDatabase) -> T): T {
		// The same verdict lookup() acts on, surfaced as a throw because this returns the block's
		// value: could-not-open (or closed) is terminal here too, not a checkNotNull accident.
		check(openIfNeeded()) { "documentation database '$databaseFile' is not open" }
		swapDatabaseIfChanged()

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

	/** The last-modified time of [file], or -1 when it does not exist. */
	private fun timestampOf(
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

	/** Closes the handle for good: no later call reopens it (see [openIfNeeded]). */
	override fun close() {
		databaseLock.write {
			closed = true
			try {
				database?.close()
			} catch (e: Exception) {
				log.error("Cannot close the documentation database: {}", e.message)
			}
			database = null
		}
	}

	/**
	 * True once the database is open. Failure is logged, not thrown: a caller falls back instead.
	 * Terminal after [close]: reopening then would hand out a handle whose owner has already done
	 * its one close, so nothing would ever release it.
	 */
	private fun openIfNeeded(): Boolean {
		if (closed) return false
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
		//
		// Best-effort, unlike the call inside the decode: this is an optimisation, and letting a
		// transient failure here propagate would fail *every* lookup -- including rows with
		// compression = 'none', which need no dictionary at all. The staleness flag is left set, so
		// the next read retries, and a brotli row that genuinely cannot resolve its dictionary still
		// fails loudly from decompressBrotli.
		try {
			compressionDictionary(database)
		} catch (e: Exception) {
			log.warn("Could not prime the compression dictionary; will retry on the next read: {}", e.message)
		}

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
	 * Loads brotli4j's native library if nothing else has yet, and turns its absence into a failed
	 * read rather than a dead app.
	 *
	 * Nothing here owns that load: it happens as a side effect of `AssetsInstallationHelper`'s
	 * install or `ToolsManager`'s tooling-jar update, neither of which runs on an ordinary cold
	 * start. A process that skips both reaches the first brotli row with the natives unregistered,
	 * and `DecoderJNI.nativeCreate` raises `UnsatisfiedLinkError` -- an Error, not an Exception, so
	 * it escapes every `catch (e: Exception)` between here and the accept loop and kills the app
	 * (observed on-device, 20-Aug). The guard lived in `WebServer.decompressBrotli`; when the decode
	 * moved here it had to move with it.
	 *
	 * Referencing [Brotli4jLoader] triggers the static init that performs the load, so this call is
	 * the warm-up; afterwards `ensureAvailability` is a single static null-check, cheap enough to
	 * leave on the per-decode path.
	 */
	private fun ensureBrotliAvailable() {
		try {
			Brotli4jLoader.ensureAvailability()
		} catch (e: UnsatisfiedLinkError) {
			throw IOException("brotli4j's native library is unavailable, so brotli content cannot be decoded", e)
		}
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
		ensureBrotliAvailable()
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
	 * Applies whichever pending file change would leave the active handle stale: a newer sdcard
	 * debug database, or the installed file rewritten in place. Must not be called with the read
	 * lock held -- the swaps take the write lock, and this lock does not upgrade.
	 *
	 * The stats behind both checks are rate-limited by one shared gate: the debug path is
	 * FUSE-backed emulated storage and a developer-only override, so it used to cost a stat on
	 * every single request (ADFA-5175).
	 */
	private fun swapDatabaseIfChanged() {
		if (!swapCheckDue()) return
		if (swapDebugDatabaseIfNewer()) return
		reopenInstalledDatabaseIfRewritten()
	}

	/** True at most once per check interval, no matter how many threads ask. */
	private fun swapCheckDue(): Boolean {
		val now = System.nanoTime()
		val last = lastSwapCheckNanos.get()
		if (now - last < debugCheckIntervalNanos) return false
		return lastSwapCheckNanos.compareAndSet(last, now)
	}

	/**
	 * Swaps to the sdcard debug database when a newer one has appeared. Returns true when a debug
	 * override is pending -- swapped just now, or marked failed until its file changes -- so the
	 * caller skips the installed-file check that would otherwise race it.
	 */
	private fun swapDebugDatabaseIfNewer(): Boolean {
		val debugTimestamp = timestampOf(debugDatabaseFile)
		if (debugTimestamp <= databaseTimestamp || debugTimestamp == failedDebugSwapTimestamp) return false

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
		return true
	}

	/**
	 * Reopens the installed database when its file has been rewritten under the cached handle.
	 * `BundledAssetsInstaller` and `SplitAssetsInstaller` both install by truncating and rewriting
	 * `Environment.DOC_DB` in place, so a handle opened before an install or update refers to pages
	 * that no longer exist and serves corrupt reads for the rest of the process (ADFA-5176 review).
	 * Detected by timestamp like the debug swap, and the swap is the same machinery, so the compiled
	 * templates, the dictionary and the generation counter invalidate identically.
	 */
	private fun reopenInstalledDatabaseIfRewritten() {
		if (activeDatabasePath != databaseFile.absolutePath) return

		val installedTimestamp = timestampOf(databaseFile)
		if (installedTimestamp == databaseTimestamp || installedTimestamp == failedInstalledSwapTimestamp) return

		databaseLock.write {
			if (installedTimestamp == databaseTimestamp || installedTimestamp == failedInstalledSwapTimestamp) return@write

			try {
				switchToDatabase(databaseFile.absolutePath, installedTimestamp)
				failedInstalledSwapTimestamp = -1
				log.info("Installed database '{}' was rewritten in place; reopened it.", databaseFile)
			} catch (e: Exception) {
				// Typically an installer still streaming into the file. The timestamp moves again
				// when it finishes, which is what retries this.
				failedInstalledSwapTimestamp = installedTimestamp
				log.error(
					"Cannot reopen the rewritten installed database '{}'; ignoring it until it changes: {}",
					databaseFile,
					e.message,
				)
			}
		}
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
		activeDatabasePath = path
		databaseTimestamp = timestamp
		compressionDictionaryStale = true
		templateCache.clear()
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
