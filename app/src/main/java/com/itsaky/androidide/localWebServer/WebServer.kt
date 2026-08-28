package com.itsaky.androidide.localWebServer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.TrafficStats
import android.os.Environment.getExternalStorageDirectory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.utils.BrotliDictionaryCodec
import com.itsaky.androidide.utils.ContentTypeHeaders
import com.itsaky.androidide.utils.DatabaseVersionResolver
import com.itsaky.androidide.utils.loadCompressionDictionary
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import io.pebbletemplates.pebble.template.PebbleTemplate
import okio.ByteString.Companion.toByteString
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.io.SequenceInputStream
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ServerConfig(
	val port: Int = 6174,
	val databasePath: String,
	val fileDirPath: String,
	val bindName: String = "localhost",
	val debugDatabasePath: String =
		getExternalStorageDirectory().toString() +
			"/Download/documentation.db",
	val debugEnablePath: String =
		getExternalStorageDirectory().toString() +
			"/Download/CodeOnTheGo.webserver.debug",
	val experimentsEnablePath: String =
		getExternalStorageDirectory().toString() +
			"/Download/CodeOnTheGo.exp",
	// TODO: Centralize this concept. --DS, 9-Feb-2026
	val clearCacheEnablePath: String =
		getExternalStorageDirectory().toString() +
			"/Download/CodeOnTheGo.webserver.cs0",
	// Yes, this is hack code.
	val projectDatabasePath: String = "/data/data/com.itsaky.androidide/databases/RecentProject_database",
)

/**
 * The `bookshelf` template's JSON context: the keys the template reads, and what SQLite's JSON1
 * functions used to emit before ADFA-5179.
 *
 * Every key is spelled out with [SerializedName] rather than left to gson's reflection over field
 * names. The template reads these names literally -- `{{ item.category }}`, `book.pdf` -- and a
 * renamed field would produce a page of blanks with nothing failing anywhere. Today `-dontobfuscate`
 * happens to keep the field names intact in release builds, but that is a global build flag two
 * tickets are actively changing, not a contract this payload can rely on.
 */
internal data class Bookshelf(
	@SerializedName("result") val result: List<BookshelfCategory>,
)

internal data class BookshelfCategory(
	@SerializedName("category") val category: String,
	@SerializedName("description") val description: String?,
	@SerializedName("books") val books: List<BookshelfBook>,
)

// Not part of the JSON payload: the accumulator readBookshelf groups rows into. Its fields become
// BookshelfCategory's once every row has been read.
private class CategoryGroup(
	val description: String?,
	val books: MutableList<BookshelfBook> = mutableListOf(),
)

internal data class BookshelfBook(
	@SerializedName("title") val title: String,
	@SerializedName("description") val description: String?,
	@SerializedName("link") val link: String,
	/** 1 or 0, not a boolean: the shape the template already expects. */
	@SerializedName("pdf") val pdf: Int,
)

data class JavaExecutionResult(
	val compileOutput: String,
	val runOutput: String,
	val timedOut: Boolean,
	val compileTimeMs: Long,
	val timeoutLimit: Long,
)

/**
 * Reads [chunks] back to back as one stream, without concatenating them into a new array.
 */
internal fun chunksAsStream(chunks: List<ByteArray>): InputStream =
	SequenceInputStream(Collections.enumeration(chunks.map { ByteArrayInputStream(it) }))

/**
 * Joins [chunks] into one exactly-sized array. A ByteArrayOutputStream would repeatedly double its
 * buffer and then hand back a second full copy -- avoidable here since the total is known up front.
 * Returns the sole element as-is when there is nothing to join.
 */
internal fun joinChunks(chunks: List<ByteArray>): ByteArray {
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

class WebServer(
	private val config: ServerConfig,
) {
	// Guards serverSocket's creation/bind (in start(), on a background thread) against a
	// concurrent close (in stop(), typically from the main thread on Activity#onDestroy()).
	// Without this, a stop() arriving before start() reaches bind() finds serverSocket not
	// yet initialized and is a silent no-op (see stop()'s isInitialized check below) -- the
	// socket then binds anyway a moment later, orphaned, and holds the port until the process
	// dies. The next start() attempt on that port then fails with "Address already in use."
	private val lifecycleLock = Any()
	private var stopRequested = false
	private lateinit var serverSocket: ServerSocket
	private lateinit var database: SQLiteDatabase
	private var databaseTimestamp: Long = -1

	// Timestamp of a debug database whose swap already failed, so a corrupt or unreadable one
	// isn't reopened on every single request (it is checked per request). A newer copy has a
	// different timestamp and is retried, which is the case that matters -- the developer
	// replacing the file is exactly how they'd fix it.
	private var failedDebugSwapTimestamp: Long = -1

	// Decodes Content's brotli rows against the shared dictionary they were compressed with (see
	// ADFA-5153). Lazily (re)built on demand, right before the first content fetch that needs it
	// after `database` changes -- see compressionDictionaryStale -- rather than eagerly at
	// database-open/swap time, but still cached (not rebuilt per-request) for the currently active
	// database. Holds no dictionary, and so decodes plain brotli, unless the active database
	// declares MAJOR >= MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY in ADFA-5220's version table.
	private var codec = BrotliDictionaryCodec(null)

	// Set whenever `database` changes (see switchToDatabase); cleared once codec has been
	// (re)built for that database. Lets the dictionary stay lazily loaded -- only right before the
	// first content fetch that actually needs it -- while still loading at most once per database
	// change rather than once per request.
	private var compressionDictionaryStale = true
	private val log = LoggerFactory.getLogger(WebServer::class.java)
	private val debugEnabled: Boolean = File(config.debugEnablePath).exists()

	// TODO: Use the centralized experiments flag instead of this ad-hoc check. --DS, 10-Feb-2026
	// Frozen at startup; restart the server to pick up a change.
	private val experimentsEnabled: Boolean = File(config.experimentsEnablePath).exists()

	// Frozen at startup; restart the server to pick up a change.
	private val clearCacheEnabled: Boolean = File(config.clearCacheEnablePath).exists()
	private val pebbleEngine = PebbleEngine.Builder().loader(StringLoader()).build()
	private val templateCache = ConcurrentHashMap<Int, PebbleTemplate>()
	private val gson: Gson =
		GsonBuilder()
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			// JSON_OBJECT emitted "description": null for a null column, and the bookshelf template
			// was written against that; gson would drop the key entirely by default.
			.serializeNulls()
			.create()
	private val dbContextType = object : TypeToken<Map<String, Any>>() {}.type

	private var bookshelfTemplateId: Int = -1
	private val httpInternalServerError = 500
	private val httpNotFound = 404

	private val contentChunkSize = 1024 * 1024

	/** Where a book whose category row has no label is filed (see [readBookshelf]). */
	private val uncategorizedLabel = "General"

	// function to obtain the last modified date of a documentation.db database
	// this is used to see if there is a newer version of the database on the sdcard
	fun getDatabaseTimestamp(
		pathname: String,
		silent: Boolean = false,
	): Long {
		val dbFile = File(pathname)
		var timestamp: Long = -1

		if (dbFile.exists()) {
			timestamp = dbFile.lastModified()

			if (!silent) {
				val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

				if (debugEnabled) log.debug("{} was last modified at {}.", pathname, dateFormat.format(Date(timestamp)))
			}
		}

		return timestamp
	}

	fun logDatabaseLastChanged() {
		try {
			log.debug("Database last change: {}.", DatabaseVersionResolver.resolveDatabaseVersion(database))
		} catch (e: Exception) {
			log.error("Could not retrieve database last change info: {}", e.message)
		}
	}

	/**
	 * Opens [path] as the active database, refreshing every piece of state that depends on which
	 * database file is active -- [databaseTimestamp] and the per-database caches
	 * [bookshelfTemplateId]/[templateCache] -- as one atomic operation. Does *not* load
	 * [compressionDictionary] itself -- a different database can have a different dictionary (or
	 * none) -- it only marks [compressionDictionaryStale] so the next content fetch that needs it
	 * loads it lazily then (see [handleClient]), at most once per database change rather than
	 * once per request. Only closes the previous database once the new one has opened
	 * successfully, so a failed swap (this throws) leaves the previous, still-open database
	 * serving requests rather than leaving [database] referencing an already-closed handle.
	 */
	private fun switchToDatabase(
		path: String,
		timestamp: Long,
	) {
		val newDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
		if (::database.isInitialized) {
			try {
				database.close()
			} catch (e: Exception) {
				log.error("Cannot close previous database: {}", e.message)
			}
		}
		database = newDatabase
		databaseTimestamp = timestamp
		compressionDictionaryStale = true
		bookshelfTemplateId = -1
		templateCache.clear()
	}

	/**
	 * Decompresses one Brotli-compressed Content row, attaching the shared dictionary when the
	 * active database declares one. Every brotli row in such a database is compressed against it,
	 * whether built offline or contributed by a plugin (ADFA-5240), so a single decode is enough
	 * and a failure is a real failure -- not, as it once was, a row that might simply have been
	 * written the other way.
	 */
	private fun decompressBrotli(chunks: List<ByteArray>): ByteArray = codec.decompress(chunksAsStream(chunks))

	/**
	 * Stops the server by closing the listening socket. Safe to call from any thread.
	 * Causes [start]'s accept loop to exit. If [start] hasn't bound the socket yet --
	 * including if it hasn't been called at all -- this still records that a stop was
	 * requested, so [start] aborts before binding instead of leaving an orphaned,
	 * unstoppable listener; only the socket-close side of shutdown is a no-op then.
	 */
	fun stop() {
		synchronized(lifecycleLock) {
			stopRequested = true
			if (!::serverSocket.isInitialized) return
			try {
				serverSocket.close()
			} catch (e: Exception) {
				log.error("Cannot close server socket: {}", e.message)
			}
		}
	}

	fun start() {
		//  Hal Eisen: Required to fix StrictMode.VmPolicy.Builder.detectUntaggedSockets()
		TrafficStats.setThreadStatsTag(0xC0DE)
		try {
			log.info(
				"Starting WebServer on {}, port {}, debugEnabled={}, debugEnablePath='{}', " +
					"debugDatabasePath='{}', experimentsEnabled={}, experimentsEnablePath='{}'.",
				config.bindName,
				config.port,
				debugEnabled,
				config.debugEnablePath,
				config.debugDatabasePath,
				experimentsEnabled,
				config.experimentsEnablePath,
			)

			try {
				switchToDatabase(config.databasePath, getDatabaseTimestamp(config.databasePath))
			} catch (e: Exception) {
				log.error("Cannot open database: {}", e.message)
				return
			}

			// NEW FEATURE: Log database metadata when debug is enabled
			if (debugEnabled) logDatabaseLastChanged()

			synchronized(lifecycleLock) {
				if (stopRequested) {
					log.info("WebServer start() aborted: stop() was called before the socket could be bound.")
					return
				}
				serverSocket = ServerSocket().apply { reuseAddress = true }
				serverSocket.bind(InetSocketAddress(config.bindName, config.port))
			}
			log.info("WebServer started successfully on '{}', port {}.", config.bindName, config.port)

			while (true) {
				var clientSocket: Socket? = null
				try {
					try {
						if (debugEnabled) log.debug("About to call accept() on the server socket, {}.", serverSocket)
						clientSocket = serverSocket.accept()

						if (debugEnabled) log.debug("Returned from socket accept(), clientSocket is {}.", clientSocket)
					} catch (e: java.net.SocketException) {
						// SLF4J placeholders produce wrong formatting here. --DS, 23-Feb-2026
						if (debugEnabled) log.debug("Caught java.net.SocketException '$e'.")

						if (e.message?.contains("Closed", ignoreCase = true) == true) {
							if (debugEnabled) log.debug("WebServer socket closed, shutting down.")
							break
						}
						log.error("Accept() failed: {}", e.message)
						continue
					}
					try {
						clientSocket?.let { handleClient(it) }
					} catch (e: Exception) {
						// SLF4J placeholders produce wrong formatting here. --DS, 23-Feb-2026
						if (debugEnabled) log.debug("Caught exception '$e'.")

						if (e is java.net.SocketException && e.message?.contains("Closed", ignoreCase = true) == true) {
							if (debugEnabled) log.debug("Client disconnected: {}", e.message)
						} else {
							log.error("Error handling client: {}", e.message)
							clientSocket?.let { socket ->
								try {
									val output = socket.outputStream

									sendError(PrintWriter(output, true), output, httpInternalServerError, "Internal Server Error 1")
								} catch (e2: Exception) {
									log.error("Error sending error response: {}", e2.message)
								}
							}
						}
					}
				} finally {
					clientSocket?.close()

					// CodeRabbit objects to the following line because clientSocket may print out as "null." This is intentional. --DS
					if (debugEnabled) log.debug("clientSocket was {}.", clientSocket)
				}
			}
		} catch (e: Exception) {
			log.error("Error: {}", e.message)
		} finally {
			if (::serverSocket.isInitialized) {
				serverSocket.close()
			}
			// database is opened before the stopRequested check that can abort start()
			// early (and before the accept loop on every other exit path), so it must be
			// closed here too, not just serverSocket -- isInitialized guards the case
			// where opening it above failed and this finally still runs.
			if (::database.isInitialized) {
				try {
					database.close()
				} catch (e: Exception) {
					log.error("Cannot close database: {}", e.message)
				}
			}
			TrafficStats.clearThreadStatsTag()
		}
	}

	/**
	 * Reads a single line from the stream (bytes until newline). Same stream is used for headers
	 * and body so POST body bytes are not lost to a separate buffered reader. HTTP header lines are ASCII.
	 */
	private fun readLineFromStream(input: InputStream): String? {
		val baos = ByteArrayOutputStream()
		while (true) {
			val b = input.read()
			if (b == -1) return if (baos.size() == 0) null else baos.toString(Charsets.ISO_8859_1).trimEnd('\r')
			if (b == '\n'.code) break
			baos.write(b)
		}
		val bytes = baos.toByteArray()
		val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
		return String(bytes, 0, len, Charsets.ISO_8859_1)
	}

	private fun handleClient(clientSocket: Socket) {
		// Whether the client has already been told 200. Read by the error path, which must not put a
		// second status line on a response that has already started (see the assignment below).
		var responseStarted = false

		if (debugEnabled) log.debug("In handleClient(), socket is {}.", clientSocket)

		val input = clientSocket.getInputStream()
		if (debugEnabled) log.debug("  input is {}.", input)

		val output = clientSocket.getOutputStream()
		if (debugEnabled) log.debug("  output is {}.", output)

		val writer = PrintWriter(output, true)
		if (debugEnabled) log.debug("  writer is {}.", writer)

		// Read the request method line, it is always the first line of the request
		var requestLine = readLineFromStream(input)
		if (requestLine == null) {
			if (debugEnabled) log.debug("requestLine is null. Returning from handleClient() early.")
			return
		}
		if (debugEnabled) log.debug("Request is {}", requestLine)

		// Parse the request
		// Request line should look like "GET /a/b/c.html HTTP/1.1"
		val parts = requestLine.split(" ")
		if (parts.size != 3) {
			return sendError(writer, output, 400, "Bad Request")
		}

		// extract the request method (e.g. GET, POST, PUT)
		val method = parts[0]
		var path = parts[1].split("?")[0] // Discard any HTTP query parameters.
		path = path.substring(1)

		// Read all headers until blank line (needed for Content-Length on POST)
		val headers = mutableMapOf<String, String>()
		while (true) {
			requestLine = readLineFromStream(input) ?: break
			if (requestLine.isEmpty()) break
			if (debugEnabled) log.debug("Header: {}", requestLine)
			val colon = requestLine.indexOf(':')
			if (colon > 0) {
				headers[requestLine.substring(0, colon).trim().lowercase()] = requestLine.substring(colon + 1).trim()
			}
		}

		// Playground endpoint: POST only, handled before GET-only check
		if (false && path == "playground/execute") {
			return handlePlaygroundExecute(input, writer, output, method, headers)
		}

		// we only support teh GET method, return an error page for anything else
		if (method != "GET") {
			return sendError(writer, output, 501, "Not Implemented")
		}

		// check to see if there is a newer version of the documentation.db database on the sdcard
		// if there is use that for our responses
		val debugDatabaseTimestamp = getDatabaseTimestamp(config.debugDatabasePath, true)
		if (debugDatabaseTimestamp > databaseTimestamp && debugDatabaseTimestamp != failedDebugSwapTimestamp) {
			try {
				switchToDatabase(config.debugDatabasePath, debugDatabaseTimestamp)
				failedDebugSwapTimestamp = -1
			} catch (e: Exception) {
				failedDebugSwapTimestamp = debugDatabaseTimestamp
				log.error(
					"Cannot swap to debug database '{}'; ignoring it until it changes: {}",
					config.debugDatabasePath,
					e.message,
				)
			}
		}

		// Handle the special "pr" endpoint with highest priority
		if (path.startsWith("pr/", false)) {
			if (debugEnabled) log.debug("Found a pr/ path, '{}'.", path)

			return when (path) {
				"pr/bs" -> handleBsEndpoint(writer, output)
				"pr/db" -> handleDbEndpoint(writer, output)
				"pr/pr" -> handlePrEndpoint(writer, output)
				"pr/ex" -> handleExEndpoint(writer, output)
				else -> sendError(writer, output, httpNotFound, "Not Found", "Path requested: '$path'.")
			}
		}

		// Lazily (re)loaded here -- the one place the dictionary is actually consumed (see
		// decompressBrotli) -- rather than eagerly at database-open/swap time, but only once per
		// database change: a swap (just above) marks compressionDictionaryStale rather than
		// reloading immediately, so this only hits the database again when that flag is set.
		// Only clears the flag on a clean load (definitive dictionary or definitive absence) --
		// an unexpected exception leaves it set so the next request retries, rather than caching
		// a transient failure as "no dictionary" for the rest of this database's lifetime.
		if (compressionDictionaryStale) {
			try {
				codec = BrotliDictionaryCodec(loadCompressionDictionary(database))
				compressionDictionaryStale = false
			} catch (e: Exception) {
				log.error("Could not load compression dictionary; will retry on the next request: {}", e.message)
			}
		}

		// Database fetch
		val query = """
			SELECT C.content, CT.value, CT.compression, C.templateId
			FROM   Content C, ContentTypes CT
			WHERE  C.contentTypeID = CT.id
			AND  C.path = ?
		"""
		val cursor = database.rawQuery(query, arrayOf(path))

		// Process database fetch
		try {
			if (cursor.count != 1) {
				return if (cursor.count == 0) {
					sendError(writer, output, httpNotFound, "Not Found")
				} else {
					sendError(
						writer,
						output,
						httpInternalServerError,
						"Corrupt database - multiple records found when unique record expected, Path requested: '$path'.",
					)
				}
			}

			cursor.moveToFirst()
			val firstChunk = cursor.getBlob(0)
			val dbMimeType = cursor.getString(1)
			var compression = cursor.getString(2)
			val templateId = cursor.getInt(3)

			// Fragment handling for large content (> 1MB). The chunks stay a list rather than
			// being eagerly concatenated: the old accumulate-into-a-ByteArrayOutputStream-then-copy
			// held both the doubling buffer and its toByteArray() copy of the *compressed* chunks
			// live at once, on top of the decompressed output that follows -- for the largest
			// bundled PDF (8.8 MB over 9 chunks) that's a real, if partial, reduction: the
			// decompressed output still goes through a comparable accumulate-then-copy in
			// decompressBrotli's own readBytes() call, so the compressed-side saving here doesn't
			// eliminate that separate transient.
			val chunks = mutableListOf(firstChunk)
			if (firstChunk.size == contentChunkSize) {
				val query2 = "SELECT content FROM Content WHERE path = ? AND languageId = 1"
				var fragmentNumber = 1
				var nextChunk = firstChunk
				while (nextChunk.size == contentChunkSize) {
					val path2 = "$path-$fragmentNumber"
					val cursor2 = database.rawQuery(query2, arrayOf(path2))
					// Whether the client has already been told 200; see the assignment below.
					var responseStarted = false
					try {
						if (cursor2.moveToFirst()) {
							nextChunk = cursor2.getBlob(0)
							chunks.add(nextChunk)
							fragmentNumber++
						} else {
							break
						}
					} finally {
						cursor2.close()
					}
				}
			}

			// Content is compressed at rest with brotli, against the shared dictionary in databases
			// that declare one (see ADFA-5153) and plain in those that don't. This server always
			// decompresses before responding, so it never needs to negotiate Content-Encoding with
			// the client.
			var dbContent =
				if (compression == "brotli") {
					compression = "none"
					decompressBrotli(chunks)
				} else {
					joinChunks(chunks)
				}

			// If the file is associated with a template, instantiate that template and send the result to the client
			if (templateId > 0) {
				dbContent = instantiatePebbleTemplate(templateId, dbContent, path, dbMimeType, compression)
			}

			// Built before the status line goes out: everything after the first println is on the
			// wire (the writer autoflushes), so a throw past that point cannot be answered with a
			// fresh error response. dbMimeType is a platform type from Cursor.getString, so a NULL
			// ContentTypes.value throws here rather than there.
			val contentTypeHeader = ContentTypeHeaders.headerValue(dbMimeType)

			writer.println("HTTP/1.1 200 OK")
			// From here on the client has been told 200. A failure while writing the body -- a
			// dropped connection is the common one -- must not append a second status line to that
			// response; sendError writes nothing at all when told the output has started, which is
			// the only honest thing left to do with a half-sent reply.
			responseStarted = true
			writer.println("Content-Type: $contentTypeHeader")
			writer.println("Content-Length: ${dbContent.size}")
			writer.println("Connection: close")
			writer.println()
			writer.flush()
			output.write(dbContent)
			output.flush()
		} catch (e: Exception) {
			log.error("Error processing request: {}", e.message, e)
			sendError(
				writer,
				output,
				httpInternalServerError,
				"Internal Server Error",
				e.message ?: "",
				outputStarted = responseStarted,
			)
		} finally {
			cursor.close()
		}
	}

	/**
	 * Renders a Pebble template identified by `templateId` using the provided JSON data and returns the rendered output as bytes.
	 *
	 * @param templateId The database ID of the Pebble template to load and compile.
	 * @param dbContent JSON bytes that will be parsed and supplied as the template context.
	 * @param path The request/content path associated with this template (used for diagnostic/logging purposes).
	 * @param dbMimeType The MIME type of the stored content (used for diagnostic/logging purposes).
	 * @param compression The compression label of the stored content (always "none" by this point, since decompression already happened) (used for diagnostic/logging purposes).
	 * @return The rendered template encoded as UTF-8 bytes.
	 * @throws Exception If the template ID is not found, is duplicated in the database, or if template lookup/instantiation fails.
	 */
	private fun instantiatePebbleTemplate(
		templateId: Int,
		dbContent: ByteArray,
		path: String,
		dbMimeType: String,
		compression: String,
	): ByteArray {
		if (debugEnabled) log.debug("Processing template for templateId={}", templateId)

		// 1. Get or Compile Template from Cache
		val compiledTemplate =
			templateCache.getOrPut(templateId) {
				if (debugEnabled) {
					log.debug(
						"Template cache miss for ID {}, path {}, MIME type {}, compression {}}",
						templateId,
						path,
						dbMimeType,
						compression,
					)
				}

				val tQuery = "SELECT content FROM Templates WHERE id = ?"
				val tCursor = database.rawQuery(tQuery, arrayOf(templateId.toString()))
				tCursor.use { cursor ->
					when {
						cursor.count == 0 -> {
							log.debug(
								"Template not found, for ID {}, path {}, MIME type {}, compression {}",
								templateId,
								path,
								dbMimeType,
								compression,
							)
							throw Exception("Template ID $templateId not found in the database")
						}

						cursor.count > 1 -> {
							log.debug(
								"More than one template found, for ID {}, path {}, MIME type {}, compression {}",
								templateId,
								path,
								dbMimeType,
								compression,
							)
							throw Exception("Template ID $templateId is shared by more than one template")
						}

						!cursor.moveToFirst() -> {
							log.debug(
								"Template not found, for ID {}, path {}, MIME type {}, compression {}",
								templateId,
								path,
								dbMimeType,
								compression,
							)
							throw Exception("Template ID $templateId not found in database.")
						}

						else -> {
							val templateBlob = cursor.getBlob(0)
							if (debugEnabled) log.debug("templateBlob = '${String(templateBlob)}'")
							pebbleEngine.getTemplate(templateBlob.toString(Charsets.UTF_8))
						}
					}
				}
			}

		// Load JSON data into a template context Map<> for instantiation
		val dbContentStr = dbContent.toString(Charsets.UTF_8)
		if (dbContentStr.isBlank() || dbContentStr.trim() == "null") {
			throw Exception("Template ID $templateId has empty or null JSON context")
		}
		val context: Map<String, Any> = gson.fromJson(dbContentStr, dbContextType)

		// Evaluate template with loaded data and return the output
		val sw = StringWriter()
		compiledTemplate.evaluate(sw, context)
		return sw.toString().toByteArray()
	}

	/**
	 * Serve an HTML page showing the 20 most recent rows of the `LastChange` table.
	 *
	 * Queries the table schema to determine column names, selects the latest 20 rows
	 * ordered by `changeTime`, escapes cell values for HTML, assembles an HTML table,
	 * and writes a normal 200 HTML response to the client. On database or rendering
	 * errors a 500 error response is sent. All database cursors are closed before returning.
	 */
	private fun handleDbEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
	) {
		if (debugEnabled) log.debug("Entering handleDbEndpoint().")

		var html: String

		try {
			// First, get the schema of the LastChange table to determine column count
			val schemaQuery = "PRAGMA table_info(LastChange)"
			val schemaCursor = database.rawQuery(schemaQuery, arrayOf())

			var columnCount: Int
			var selectColumns: String

			html = getTableHtml("LastChange Table", "LastChange Table (20 Most Recent Rows)")

			try {
				columnCount = schemaCursor.count
				val columnNames = mutableListOf<String>()

				while (schemaCursor.moveToNext()) {
					// Values come from schema introspection, therefore not subject to a SQL injection attack.
					columnNames.add(schemaCursor.getString(1)) // Column name is at index 1
				}

				if (debugEnabled) {
					log.debug(
						"LastChange table has {} columns: {}",
						columnCount,
						columnNames,
					)
				}

				// Build the SELECT query for the 20 most recent rows
				selectColumns = columnNames.joinToString(", ")

				// Add header row
				html += """<tr>"""
				for (columnName in columnNames) {
					html += """<th>${escapeHtml(columnName)}</th>"""
				}
				html += """</tr>"""
			} finally {
				schemaCursor.close()
			}

			val dataQuery =
				"SELECT $selectColumns FROM LastChange ORDER BY changeTime DESC LIMIT 20"

			val dataCursor = database.rawQuery(dataQuery, arrayOf())

			try {
				val rowCount = dataCursor.count

				if (debugEnabled) log.debug("Retrieved {} rows from LastChange table", rowCount)

				// Add data rows
				while (dataCursor.moveToNext()) {
					html += """<tr>"""
					for (i in 0 until columnCount) {
						html += """<td>${escapeHtml(dataCursor.getString(i) ?: "")}</td>"""
					}
					html += """</tr>"""
				}

				html += """</table></body></html>"""
			} finally {
				dataCursor.close()
			}

			if (debugEnabled) log.debug("html is '{}'.", html)
		} catch (e: Exception) {
			log.error("Error creating output for /pr/db endpoint: {}", e.message)
			sendError(
				writer,
				output,
				httpInternalServerError,
				"Internal Server Error 4.1",
				"Error creating output.",
			)
			return
		}

		try {
			writeNormalToClient(writer, output, html)

			if (debugEnabled) log.debug("Leaving handleDbEndpoint().")
		} catch (e: Exception) {
			log.error("Error handling /pr/db endpoint: {}", e.message)
			sendError(writer, output, httpInternalServerError, "Internal Server Error 4", "Error generating database table.", true)
		}
	}

	/**
	 * Handles the /pr/bs endpoint by invoking the bookshelf generator and sending a 500 error if generation fails.
	 *
	 * Calls realHandleBsEndpoint to produce and write the response body; if an exception occurs, sends an HTTP 500
	 * error using the reported output-start state so no additional headers/body are written after output has begun.
	 *
	 * @param writer PrintWriter used for writing textual HTTP response headers.
	 * @param output Raw OutputStream used for writing the response body bytes.
	 */
	private fun handleBsEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
	) {
		if (debugEnabled) log.debug("Entering handleBsEndpoint().")
		if (clearCacheEnabled) templateCache.clear()

		var outputStarted = false

		try {
			outputStarted = realHandleBsEndpoint(writer, output) { outputStarted = true }
		} catch (e: Exception) {
			log.error("Error handling /pr/bs endpoint: {}", e.message)
			sendError(writer, output, httpInternalServerError, "Internal Server Error 6", "Error generating bookshelf HTML.", outputStarted)
		}

		if (debugEnabled) log.debug("Leaving handleBsEndpoint().")
	}

	/**
	 * Writes a small CSS response that shows or hides elements with the
	 * `.code_on_the_go_experiment` class depending on the server's
	 * `experimentsEnabled` flag.
	 */
	private fun handleExEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
	) {
		val flag = if (experimentsEnabled) "{}" else "{display: none;}"

		if (debugEnabled) log.debug("Experiment flag='{}'.", flag)

		sendCSS(writer, output, ".code_on_the_go_experiment $flag")
	}

	/**
	 * Handle the /pr/pr endpoint by opening the project database, delegating page generation
	 * to realHandlePrEndpoint, and sending an HTTP 500 error if generation fails.
	 *
	 * @param writer PrintWriter used to write response headers.
	 * @param output OutputStream used to write response body bytes.
	 */
	private fun handlePrEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
	) {
		if (debugEnabled) log.debug("Entering handlePrEndpoint().")

		var projectDatabase: SQLiteDatabase? = null
		var outputStarted = false

		try {
			projectDatabase =
				SQLiteDatabase.openDatabase(
					config.projectDatabasePath,
					null,
					SQLiteDatabase.OPEN_READONLY,
				)

			outputStarted = realHandlePrEndpoint(writer, output, projectDatabase) { outputStarted = true }
		} catch (e: Exception) {
			log.error("Error handling /pr/pr endpoint: {}", e.message)
			sendError(writer, output, httpInternalServerError, "Internal Server Error 6", "Error generating database table.", outputStarted)
		} finally {
			projectDatabase?.close()
		}

		if (debugEnabled) log.debug("Leaving handlePrEndpoint().")
	}

	/**
	 * Builds the Bookshelf content, renders it with the `bookshelf` template, and sends the resulting response to the client.
	 *
	 * @param writer PrintWriter for sending HTTP headers and control output.
	 * @param output OutputStream for writing the response body bytes.
	 * @param markOutputStarted Invoked right before the first response byte is written, so the
	 *   caller's "did we already respond" flag is accurate even if the write itself then fails
	 *   partway through -- not just after this function returns.
	 * @return `true` if the templated response was written to the client, `false` if an error response was sent or no output was produced.
	 */
	private fun realHandleBsEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
		markOutputStarted: () -> Unit,
	): Boolean {
		if (debugEnabled) log.debug("Entering realHandleBsEndpoint().")

		val jsonText: ByteArray

		try {
			jsonText = bookshelfJson(database)
			if (debugEnabled) log.debug("json content = '${String(jsonText)}'.")
			if (debugEnabled) log.debug("before fetch bookshelf template ID = '$bookshelfTemplateId'")

			if (bookshelfTemplateId == -1) {
				database.rawQuery("SELECT id FROM Templates WHERE name = 'bookshelf'", arrayOf()).use { cursor ->
					if (!isCursorOneRow(cursor, writer, output)) {
						return false
					}

					cursor.moveToFirst()
					bookshelfTemplateId = cursor.getInt(0)
					if (debugEnabled) log.debug("after the fetch bookshelf template ID = '$bookshelfTemplateId'")
				}
			}
		} catch (e: Exception) {
			log.error("Error processing request: {}", e.message)
			sendError(writer, output, httpInternalServerError, "Internal Server Error", e.message ?: "")
			return false
		}

		val result = instantiatePebbleTemplate(bookshelfTemplateId, jsonText, "/bookshelf", "application/json", "none")

		if (debugEnabled) log.debug("Bookshelf result is '{}'.", String(result))

		markOutputStarted()
		writeNormalToClient(writer, output, String(result))

		if (debugEnabled) log.debug("Leaving realHandleBsEndpoint().")

		return true
	}

	/**
	 * The exact bytes the `bookshelf` template is rendered against.
	 *
	 * Extracted so the test that pins the payload's keys, nesting and explicit nulls can call the
	 * path production uses. Asserting on a re-composed `gson.toJson(readBookshelf(...))` looked
	 * equivalent but could not fail if this line changed -- a differently configured serializer here
	 * would drop every `"description": null` the template was written against and the test would
	 * still pass.
	 */
	internal fun bookshelfJson(database: SQLiteDatabase): ByteArray {
		val bookshelf = readBookshelf(database)
		if (bookshelf.result.isEmpty()) {
			// Not an error -- the endpoint answers 200 with an empty shelf -- but it is indistinguishable
			// from a working shelf in a bug report, and it is the state ADFA-5204 produced. The query
			// this replaced surfaced it only by accident, as a 500 from reading a NULL blob.
			// "no categories", not "no rows": a row whose Content.path is NULL is skipped above, so
			// the query can return rows and still leave nothing to serve. Each skip logs its own
			// warning, which is what tells the two cases apart.
			// debugEnabled, like every other log on this path: on the database this ticket exists for,
			// where every Bookshelf row joins to nothing, the empty shelf is the steady state and this
			// would write a line on every page load.
			if (debugEnabled) log.info("No bookshelf categories to serve; serving an empty shelf.")
		}
		return gson.toJson(bookshelf).toByteArray(Charsets.UTF_8)
	}

	/**
	 * The bookshelf, grouped into categories, for the `bookshelf` template's JSON context.
	 *
	 * Assembled here rather than by SQLite's JSON1 functions (ADFA-5179): `JSON_OBJECT` and
	 * `JSON_GROUP_ARRAY` are absent from the system SQLite on some devices -- a Galaxy Note 20 Ultra
	 * on Android 13 among them -- where the old query failed at runtime with `no such function:
	 * JSON_OBJECT` and the bookshelf could not be opened at all. A plain relational query and gson
	 * work everywhere.
	 *
	 * The payload keeps its keys, nesting and explicit nulls, but two things about it do change, both
	 * deliberately:
	 *
	 * Books within a category are now genuinely sorted by title. The old `ORDER BY BC.category,
	 * B.title` was inert for them -- it ordered the *groups*, while `JSON_GROUP_ARRAY` aggregated
	 * rows in scan order, and `B.title` was a bare column under `GROUP BY BC.category`. Against the
	 * shipped database this reverses the two Java books: "Java, Java, Java" came first by insertion,
	 * and "Java Notes for Professionals" comes first by title (a space sorts before a comma).
	 * Deterministic order is worth having, but it is a visible change, not a no-op.
	 *
	 * A category whose books all have a NULL `Content.path` disappears from the page. The old query
	 * emitted the section with `"link": null` in it -- visibly broken, but present -- because the JSON
	 * was built per row before any filtering. Here the row is skipped before its group is created, so
	 * an entire category can vanish with only a log line to say so. Skipping a row that cannot be
	 * linked is still right; the section going with it is the part worth knowing.
	 *
	 * The `pdf` flag is now case-insensitive. `SUBSTR(C.path, -4) == '.pdf'` compared under BINARY
	 * collation, so a row at `books/Guide.PDF` was flagged 0 and rendered as a web link. No shipped
	 * row spells the extension any other way -- checked with `GLOB '*.[Pp][Dd][Ff]'` -- so nothing
	 * changes today; a future upper-case path is simply treated as the PDF it is.
	 *
	 * An empty bookshelf comes back as an empty list, which the template renders as an empty page.
	 * The old query turned that case into an HTTP 500: `group_concat` over no rows is NULL, so the
	 * concatenated JSON was NULL and reading it as a blob threw. Worth knowing, because the rows in
	 * at least one `documentation.db` copy have a NULL `bookCategoryID` and so join to nothing.
	 */
	internal fun readBookshelf(database: SQLiteDatabase): Bookshelf {
		// The two fallbacks the old query expressed as IFNULL live in Kotlin now (see below): they
		// are easier to see there, and a unit test can cover them.
		val query =
			"""
SELECT BC.category,
	BC.description,
	B.title,
	B.description,
	C.path,
	-- Only for the diagnostic below. Appended, not inserted: every read here is by positional
	-- index, so a column added anywhere else silently re-points the five above it.
	C.id
FROM Content AS C,
	Bookshelf AS B,
	BookCategories AS BC
WHERE C.id = B.contentID
AND   B.bookCategoryID = BC.id
-- COALESCE and NOCASE so the sort key is the string the page shows: the title falls back to the
-- path when it is NULL, and BINARY collation would otherwise put every capitalised title ahead of
-- every lower-case one and NULL titles ahead of everything.
ORDER BY BC.category,
	COALESCE(B.title, C.path) COLLATE NOCASE
			""".trimIndent()

		// LinkedHashMap: the query's ORDER BY decides the order categories and books appear in, and
		// the template renders them in that order.
		//
		// Keyed by the *raw* category, null included. The query this replaced grouped by BC.category,
		// where NULL and a literal "General" are two groups that both render as "General"; coalescing
		// before grouping merges them and keeps only the first description. This port is meant to
		// change nothing, so the label is applied at construction instead.
		// One entry per category, holding the label's own description alongside its books. Two maps
		// keyed by the same category would have to be kept in agreement by hand, and putIfAbsent is
		// the wrong tool for that: java.util.Map treats a key mapped to null as absent, so a category
		// whose first row had a NULL description was overwritten by the next row's -- the opposite of
		// the "first one wins" this comment used to claim. getOrPut's lambda runs only when the key
		// is genuinely missing, so the description is read once, at group creation, and there is no
		// second write to get wrong.
		//
		// The value type has to stay non-null for that to hold: getOrPut treats a null *value* as
		// absent too, so a LinkedHashMap<String?, String?> of descriptions would reintroduce the bug
		// in a different shape.
		val categories = LinkedHashMap<String?, CategoryGroup>()

		database.rawQuery(query, arrayOf()).use { cursor ->
			while (cursor.moveToNext()) {
				// Content.path is NOT NULL in the maintained schema, so this is unreachable there -- but
				// this endpoint exists because a shipped documentation.db had NULLs nobody expected, and
				// a platform-type null reaching BookshelfBook(link: String) is an NPE that costs the
				// whole shelf rather than the one bad row.
				val path = cursor.getString(4)
				if (path == null) {
					// Index 5, C.id -- the title at index 2 is not an id, and in this branch it is
					// often null too, so it identified nothing while claiming to.
					// Also gated: one line per malformed row per request is unbounded, and the rows do not
					// change between requests.
					if (debugEnabled) log.warn("Bookshelf row for content id {} has no path; skipping it.", cursor.getString(5))
					continue
				}
				// BookCategories.category is nullable, so a book can be linked to a category row that
				// has no label; it is labelled "General" below, as the old query's IFNULL had it. This
				// is *not* about a book with no category at all -- the join drops those, exactly as
				// the query this replaced did.
				val category = cursor.getString(0)

				categories
					.getOrPut(category) { CategoryGroup(cursor.getString(1)) }
					.books
					.add(
						BookshelfBook(
							// A book with no title of its own shows its path, again as before.
							title = cursor.getString(2) ?: path,
							description = cursor.getString(3),
							link = path,
							// 1/0 rather than a boolean: what the template has always received.
							pdf = if (path.endsWith(".pdf", ignoreCase = true)) 1 else 0,
						),
					)
			}
		}

		return Bookshelf(
			categories.map { (category, group) ->
				BookshelfCategory(
					category = category ?: uncategorizedLabel,
					description = group.description,
					// toList(): BookshelfCategory.books is a List, and handing over the accumulator's own
					// MutableList would let a future caller that keeps the map mutate it afterwards.
					books = group.books.toList(),
				)
			},
		)
	}

	private fun isCursorOneRow(
		cursor: Cursor,
		writer: PrintWriter,
		output: java.io.OutputStream,
	): Boolean {
		if (cursor.count == 1) {
			return true
		}
		if (cursor.count == 0) {
			sendError(writer, output, httpNotFound, "Corrupt database, no rows found, expected one.")
		} else {
			sendError(writer, output, httpInternalServerError, "Corrupt database - found ${cursor.count} rows when 1 was expected.")
		}
		return false
	}

	/**
	 * Builds an HTML table of recent projects from the provided project database and writes it to the client.
	 *
	 * @param writer PrintWriter used for writing HTTP response headers.
	 * @param output OutputStream used for writing the HTTP response body.
	 * @param projectDatabase Read-only SQLiteDatabase containing the `recent_project_table`.
	 * @param markOutputStarted Invoked right before the first response byte is written, so the
	 *   caller's "did we already respond" flag is accurate even if the write itself then fails
	 *   partway through -- not just after this function returns.
	 * @return `true` if an HTML response was written to the client.
	 */
	private fun realHandlePrEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
		projectDatabase: SQLiteDatabase,
		markOutputStarted: () -> Unit,
	): Boolean {
		if (debugEnabled) log.debug("Entering realHandlePrEndpoint().")

		val query = """
SELECT id,
	name,
	DATETIME(create_at     / 1000, 'unixepoch'),
	DATETIME(last_modified / 1000, 'unixepoch'),
	location,
	template_name,
	language
FROM     recent_project_table
ORDER BY last_modified DESC"""

		var html =
			getTableHtml("Projects", "Projects") + """
<tr>
<th>Id</th>
<th>Name</th>
<th>Created</th>
<th>Modified &nbsp;&nbsp;<span style="font-family: sans-serif">V</span></th>
<th>Directory</th>
<th>Template</th>
<th>Language</th>
</tr>"""

		val cursor = projectDatabase.rawQuery(query, arrayOf())

		try {
			if (debugEnabled) log.debug("Retrieved {} rows.", cursor.count)

			while (cursor.moveToNext()) {
				html += """<tr>
<td>${escapeHtml(cursor.getString(0) ?: "")}</td>
<td>${escapeHtml(cursor.getString(1) ?: "")}</td>
<td>${escapeHtml(cursor.getString(2) ?: "")}</td>
<td>${escapeHtml(cursor.getString(3) ?: "")}</td>
<td>${escapeHtml(cursor.getString(4) ?: "")}</td>
<td>${escapeHtml(cursor.getString(5) ?: "")}</td>
<td>${escapeHtml(cursor.getString(6) ?: "")}</td>
</tr>"""
			}

			html += "</table></body></html>"
		} finally {
			cursor.close()
		}

		// May output a lot of stuff but better too much than too little. --DS, 23-Feb-2026
		if (debugEnabled) log.debug("html is '{}'.", html)

		markOutputStarted()
		writeNormalToClient(writer, output, html)

		if (debugEnabled) log.debug("Leaving realHandlePrEndpoint().")

		return true
	}

	/**
	 * Get HTML for table response page.
	 */
	private fun getTableHtml(
		title: String,
		tableName: String,
	): String {
		if (debugEnabled) log.debug("Entering getTableHtml(), title='{}', tableName='{}'.", title, tableName)

		return """<!DOCTYPE html>
<html>
<head>
<title>${escapeHtml(title)}</title>
<style>
table { border-collapse: collapse; width: 100%; }
th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
th { background-color: #f2f2f2; }
</style>
</head>
<body>
<h1>${escapeHtml(tableName)}</h1>
<table width='100%'>"""
	}

	/**
	 * Tail of writing table data back to client.
	 */
	private fun writeNormalToClient(
		writer: PrintWriter,
		output: java.io.OutputStream,
		html: String,
	) {
		if (debugEnabled) log.debug("Entering writeNormalToClient(), html='{}'.", html.take(200))

		val htmlBytes = html.toByteArray(Charsets.UTF_8)

		/*
		println() is intentional: the triple-quoted string ends with a single '\n' (after "Connection: close"),
		and println() appends the second '\n' to form the required blank-line HTTP header terminator ("\n\n"). --DS, 22-Feb-2026
		 */
		writer.println(
			"""HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Content-Length: ${htmlBytes.size}
Connection: close
""",
		)

		output.write(htmlBytes)
		output.flush()
	}

	/**
	 * Escapes HTML special characters to prevent XSS attacks.
	 * Converts <, >, &, ", and ' to their HTML entity equivalents.
	 */
	private fun escapeHtml(text: String): String {
//        if (debugEnabled) log.debug("Entering escapeHtml(), text='{}'.", text)

		return text
			.replace("&", "&amp;") // Must be first to avoid double-escaping
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#x27;")
	}

	private fun sendError(
		writer: PrintWriter,
		output: java.io.OutputStream,
		code: Int,
		message: String,
		details: String = "",
		outputStarted: Boolean = false,
	) {
		if (debugEnabled) {
			log.debug(
				"Entering sendError(), code={}, message='{}', details='{}', outputStarted={}.",
				code,
				message,
				details,
				outputStarted,
			)
		}

		val messageString = "$code $message" + if (details.isEmpty()) "" else "\n$details"
		val bodyBytes = messageString.toByteArray(Charsets.UTF_8)

		if (!outputStarted) {
			writer.println(
				"""HTTP/1.1 $code $message
Content-Type: text/plain; charset=utf-8
Content-Length: ${bodyBytes.size}
Connection: close
""",
			)
			output.write(bodyBytes)
			output.flush()
		}
		if (debugEnabled) log.debug("Leaving sendError().")
	}

	private fun sendCSS(
		writer: PrintWriter,
		output: java.io.OutputStream,
		message: String,
	) {
		if (debugEnabled) log.debug("Entering sendCSS(), message='{}'.", message)

		val bodyBytes = message.toByteArray(Charsets.UTF_8)

		writer.println(
			"""HTTP/1.1 200 OK
Content-Type: text/css; charset=utf-8
Content-Length: ${bodyBytes.size}
Cache-Control: no-store
Connection: close
""",
		)

		output.write(bodyBytes)
		output.flush()

		if (debugEnabled) log.debug("Leaving sendCSS().")
	}

	private fun handlePlaygroundExecute(
		input: java.io.InputStream,
		writer: PrintWriter,
		output: java.io.OutputStream,
		method: String,
		headers: Map<String, String>,
	) {
		if (method != "POST") {
			return sendError(writer, output, 405, "Method Not Allowed")
		}
		val contentLengthStr =
			headers["content-length"] ?: run {
				return sendError(writer, output, 400, "Bad Request", "Missing Content-Length")
			}
		val contentLength =
			contentLengthStr.toIntOrNull() ?: run {
				return sendError(writer, output, 400, "Bad Request", "Invalid Content-Length")
			}
		if (contentLength <= 0) {
			return sendError(writer, output, 400, "Bad Request", "Content-Length must be positive")
		}
		if (contentLength > 10_000) {
			return sendError(writer, output, 413, "Payload Too Large")
		}
		val body = ByteArray(contentLength)
		var offset = 0
		while (offset < contentLength) {
			val read = input.read(body, offset, contentLength - offset)
			if (read <= 0) {
				return sendError(writer, output, 400, "Bad Request", "Input stream interrupted prematurely")
			}
			offset += read
		}
		val data =
			parseFormDataField(body, "data") ?: run {
				return sendError(writer, output, 400, "Bad Request", "Missing or empty form field 'data'")
			}
		if (data.size > 10_000) {
			return sendError(writer, output, 413, "Payload Too Large")
		}
		val workDir =
			File(config.fileDirPath, "playground_${System.nanoTime()}_${java.util.UUID.randomUUID()}")
				.apply { mkdirs() }
		try {
			val sourceFile = createFileFromPost(data, workDir)
			val result = compileAndRunJava(sourceFile)
			val sourceString = data.toString(Charsets.UTF_8)
			val responseBody = sourceString + result
			val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
			writer.println("HTTP/1.1 200 OK")
			writer.println("Content-Type: text/plain; charset=utf-8")
			writer.println("Content-Length: ${responseBytes.size}")
			writer.println()
			writer.flush()
			output.write(responseBytes)
			output.flush()
		} finally {
			workDir.deleteRecursively()
		}
	}

	private fun parseFormDataField(
		body: ByteArray,
		fieldName: String,
	): ByteArray? {
		val bodyStr = body.toString(Charsets.UTF_8)
		val pairs = bodyStr.split("&")
		for (pair in pairs) {
			val eq = pair.indexOf('=')
			if (eq < 0) continue
			val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
			if (key != fieldName) continue
			val value = pair.substring(eq + 1)
			val decoded = URLDecoder.decode(value, "UTF-8")
			if (decoded.isEmpty()) return null
			return decoded.toByteArray(Charsets.UTF_8)
		}
		return null
	}

	private fun createFileFromPost(
		data: ByteArray,
		workDir: File,
	): File {
		require(data.size <= 10_000) { "data exceeds 10000 bytes" }
		val file = File(workDir, "Playground.java")
		file.writeBytes(data)
		return file
	}

	private fun compileAndRunJava(sourceFile: File): String {
		val dir = sourceFile.parentFile
		val fileName = sourceFile.nameWithoutExtension
		val classFile = File(dir, "$fileName.class")
		classFile.delete()
		val directoryPath = config.fileDirPath
		val javacPath = "$directoryPath/usr/bin/javac"
		val javaPath = "$directoryPath/usr/bin/java"
		val filePath = sourceFile.absolutePath

		val compileTimeoutSec = 60L
		val runTimeoutSec = 120L
		val destroyWaitSec = 5L

		try {
			val javac =
				ProcessBuilder(javacPath, filePath)
					.directory(dir)
					.redirectErrorStream(true)
					.start()
			javac.outputStream.close()
			val compileOutputRef = AtomicReference<String>("")
			val compileReader =
				Thread {
					compileOutputRef.set(
						javac.inputStream.bufferedReader().readText(),
					)
				}
			compileReader.start()
			val compileDone =
				javac.waitFor(compileTimeoutSec, TimeUnit.SECONDS)
			if (!compileDone) {
				javac.destroyForcibly()
				javac.waitFor(destroyWaitSec, TimeUnit.SECONDS)
				compileReader.join(1000)
				return "Compilation timed out after ${compileTimeoutSec}s:\n${compileOutputRef.get()}"
			}
			compileReader.join(2000)
			val compileOutput = compileOutputRef.get()
			if (javac.exitValue() != 0) {
				return "Compilation failed:\n$compileOutput"
			}

			val java =
				ProcessBuilder(
					javaPath,
					"-cp",
					dir?.absolutePath ?: "",
					fileName,
				).directory(dir)
					.redirectErrorStream(true)
					.start()
			java.outputStream.close()
			val runOutputRef = AtomicReference<String>("")
			val runReader =
				Thread {
					runOutputRef.set(
						java.inputStream.bufferedReader().readText(),
					)
				}
			runReader.start()
			val runDone = java.waitFor(runTimeoutSec, TimeUnit.SECONDS)
			if (!runDone) {
				java.destroyForcibly()
				java.waitFor(destroyWaitSec, TimeUnit.SECONDS)
				runReader.join(1000)
				return "Execution timed out after ${runTimeoutSec}s:\n${runOutputRef.get()}"
			}
			runReader.join(2000)
			val runOutput = runOutputRef.get()

			return if (compileOutput.isNotBlank()) {
				"Compile output\n $compileOutput\n Program output\n$runOutput"
			} else {
				"Program output\n $runOutput"
			}
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			return "Compilation or execution interrupted."
		}
	}
}
