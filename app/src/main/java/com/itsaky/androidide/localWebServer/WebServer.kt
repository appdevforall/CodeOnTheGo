package com.itsaky.androidide.localWebServer

import android.database.sqlite.SQLiteDatabase
import android.net.TrafficStats
import android.os.Environment.getExternalStorageDirectory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.itsaky.androidide.documentation.DocumentationContent
import com.itsaky.androidide.documentation.DocumentationContentSource
import com.itsaky.androidide.documentation.DocumentationLookup
import com.itsaky.androidide.documentation.DocumentationRequestInterceptor
import com.itsaky.androidide.utils.ContentTypeHeaders
import com.itsaky.androidide.utils.DatabaseVersionResolver
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
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
	// ADFA-5175: how often the sdcard debug database may be stat'ed. It lives on FUSE-backed
	// emulated storage, and it is a developer-only override, so once a second is plenty.
	val debugDatabaseCheckIntervalMs: Long = 1000,
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

class WebServer(
	private val config: ServerConfig,
	// Seam for the accept-retry tests, which drive thousands of simulated failures and must not
	// actually sleep for them. Production always gets Thread.sleep.
	internal val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
	// Guards serverSocket's creation/bind (in start(), on a background thread) against a
	// concurrent close (in stop(), typically from the main thread on Activity#onDestroy()).
	// Without this, a stop() arriving before start() reaches bind() finds serverSocket not
	// yet initialized and is a silent no-op (see stop()'s isInitialized check below) -- the
	// socket then binds anyway a moment later, orphaned, and holds the port until the process
	// dies. The next start() attempt on that port then fails with "Address already in use."
	private val lifecycleLock = Any()

	// The one pipeline that reads documentation.db (ADFA-5176): row lookup, chunk reassembly,
	// dictionary-aware Brotli decode, and the sdcard debug-database swap. A WebView answers the
	// same paths through its own instance in DocumentationRequestInterceptor.
	private val contentSource =
		DocumentationContentSource(
			File(config.databasePath),
			File(config.debugDatabasePath),
			config.debugDatabaseCheckIntervalMs,
		)

	// @Volatile: written under lifecycleLock by stop(), but read by the accept loop without it --
	// see acceptLoop, which has to see a stop that happened on another thread.
	//
	// Never cleared, deliberately: a stop that arrives before the socket is bound has to keep
	// start() from binding an orphaned listener, so this is one-way and a stopped instance is
	// finished. Restarting means a new WebServer, which is what MainActivity.startWebServer does
	// -- it constructs one per start. stop() then start() on the same instance is not a recovery
	// path and never was.
	@Volatile
	private var stopRequested = false
	private lateinit var serverSocket: ServerSocket
	private val log = LoggerFactory.getLogger(WebServer::class.java)
	private val debugEnabled: Boolean = File(config.debugEnablePath).exists()

	// TODO: Use the centralized experiments flag instead of this ad-hoc check. --DS, 10-Feb-2026
	// Frozen at startup; restart the server to pick up a change.
	private val experimentsEnabled: Boolean = File(config.experimentsEnablePath).exists()

	// Frozen at startup; restart the server to pick up a change.
	private val clearCacheEnabled: Boolean = File(config.clearCacheEnablePath).exists()

	// Serializes the bookshelf payload only; the template contexts read from the database are
	// deserialized by DocumentationContentSource's own gson.
	private val gson: Gson =
		GsonBuilder()
			// JSON_OBJECT emitted "description": null for a null column, and the bookshelf template
			// was written against that; gson would drop the key entirely by default.
			.serializeNulls()
			.create()

	// Long enough to stop a descriptor-exhaustion spin starving the connections whose closing would
	// fix it; short enough to be invisible to a user, and never paid on a successful accept.
	private val initialAcceptBackoffMs = 50L

	// Doubling from 50 ms, the interval reaches this in eight failures, so a failure that persists
	// costs well under a line a second instead of twenty. That is what bounds the log volume; an
	// earlier version capped the retries instead and gave up after twenty, which closed the listener
	// and the database and left documentation dead for the rest of the process -- the ADFA-5242
	// symptom, delayed by a second. A listener that cannot accept now keeps trying: the descriptor
	// pressure that causes this comes from the rest of the process (Gradle, Termux, the editor) and
	// clears on its own timescale, not ours.
	private val maxAcceptBackoffMs = 2_000L

	// Retries between heartbeat lines once the interval stops growing: 15 x 2 s is one line every
	// 30 seconds while a failure persists.
	private val acceptHeartbeatRetries = 15L

	private val httpInternalServerError = 500
	private val httpNotFound = 404

	// Hal Eisen: required to fix StrictMode.VmPolicy.Builder.detectUntaggedSockets().
	private val socketStatsTag = 0xC0DE

	/** Where a book whose category row has no label is filed (see [readBookshelf]). */
	private val uncategorizedLabel = "General"

	/**
	 * Logs the most recent documentation database change information.
	 */
	fun logDatabaseLastChanged() {
		try {
			log.debug(
				"Database last change: {}.",
				contentSource.withDatabase { DatabaseVersionResolver.resolveDatabaseVersion(it) },
			)
		} catch (e: Exception) {
			log.error("Could not retrieve database last change info: {}", e.message)
		}
	}

	/**
	 * Requests server shutdown and closes the listening socket when it is available.
	 *
	 * Records the shutdown request even if the server has not started, preventing a later
	 * startup from binding the socket.
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

	/**
	 * Accepts connections on [socket] until it closes.
	 *
	 * Extracted from [start] so ADFA-5242's retry path can be driven by a socket whose accept()
	 * fails: the rest of start() needs a live Android runtime -- TrafficStats, SQLite -- and this
	 * loop needs neither. The bug it fixes was invisible precisely because nothing could reach here.
	 */
	internal fun acceptLoop(socket: ServerSocket) {
		// 0 means accept() has been succeeding; any other value is the interval the next retry waits.
		var backoffMs = 0L
		// Retries spent at the ceiling, so a failure that never clears keeps saying so. Without this
		// the escalation log went silent for good once the interval stopped changing.
		var retriesAtCeiling = 0L
		// Checked in the loop head, not only in the catch: stop() logs and swallows a throwing
		// serverSocket.close(), which leaves closed == false, so accept() kept succeeding and the loop
		// served on past a requested shutdown, holding the database open (ADFA-5242 review).
		while (!shouldStopAccepting(socket)) {
			val client =
				try {
					if (debugEnabled) log.debug("About to call accept() on the server socket, {}.", socket)
					socket.accept().also {
						// Halved, not zeroed. Zeroing made every failure "the first of a burst", so an
						// intermittent one -- a client that RSTs between SYN and accept(), which a WebView
						// cancelling a request produces routinely -- logged a full stack trace and stalled
						// the listener 50 ms every single time. That is the flood the backoff exists to
						// stop. Decaying means a flapping listener keeps most of its interval and a
						// genuinely recovered one is back to zero within a few accepts.
						backoffMs = if (backoffMs <= initialAcceptBackoffMs) 0L else backoffMs / 2
						// Reset on every success, not only once the interval reaches zero. The counter
						// means "consecutive retries at the ceiling", and an accept that succeeds ends
						// that run whatever the interval still is. Clearing it only at zero left a stale
						// count behind: at the ceiling with 14 retries banked, one success then a return
						// to the ceiling fired the heartbeat on the next retry instead of the fifteenth.
						retriesAtCeiling = 0L
						if (debugEnabled) log.debug("Returned from accept(), clientSocket is {}.", it)
					}
				} catch (e: IOException) {
					// IOException, not SocketException: accept() is declared to throw the wider type, and
					// "Too many open files" arrives as a bare IOException. Catching only the subtype let
					// that one unwind to start()'s outermost handler, whose finally closes the listening
					// socket and the database (ADFA-5242).
					if (debugEnabled) log.debug("Caught IOException from accept().", e)

					if (shouldStopAccepting(socket)) {
						if (debugEnabled) log.debug("WebServer socket closed, shutting down.")
						break
					}

					val previous = backoffMs
					backoffMs =
						if (previous == 0L) {
							initialAcceptBackoffMs
						} else {
							minOf(previous * 2, maxAcceptBackoffMs)
						}
					// The stack trace goes out once per burst, on the first failure. Repeats say only
					// that it is still failing, and only when the interval changes: nineteen identical
					// traces told nobody anything the first one had not. They do carry e.toString()
					// rather than e.message, so the type is still there -- a burst can change cause
					// mid-flight (EMFILE giving way to ECONNABORTED), and message alone is null for
					// some IOExceptions, which logged a bare "null".
					if (previous == 0L) {
						log.error("Accept() failed, retrying in {} ms: {}", backoffMs, e.message, e)
						retriesAtCeiling = 0L
					} else if (backoffMs != previous) {
						log.error("Accept() still failing, backing off to {} ms: {}", backoffMs, e.toString())
					} else {
						// At the ceiling the interval stops changing, so neither branch above fires again.
						// A heartbeat roughly every 30 s keeps a permanent failure visible without
						// returning to a line per retry -- the loop never gives up, so the log must not
						// either.
						retriesAtCeiling++
						if (retriesAtCeiling % acceptHeartbeatRetries == 0L) {
							log.error(
								"Accept() still failing after {} retries at {} ms: {}",
								retriesAtCeiling,
								backoffMs,
								e.toString(),
							)
						}
					}

					if (!pauseAfterFailedAccept(backoffMs)) {
						log.info("Accept loop interrupted while backing off; shutting down.")
						break
					}
					continue
				}

			// A client cannot be allowed to end the loop: anything escaping here reaches start()'s
			// handler, whose finally closes the listener and the database for everyone.
			//
			// Throwable, not Exception. joinChunks allocates the whole row in one array (1 MB per
			// chunk) and Pebble renders recursively, so one large row can raise OutOfMemoryError and a
			// pathological template a StackOverflowError -- neither an Exception, both fatal to the
			// listener through exactly the path this ticket exists to close.
			try {
				serveThenClose(client)
			} catch (e: Throwable) {
				log.error("Serving a client threw past its own handler; the listener stays up: {}", e.message, e)
			}
		}
	}

	/** Serves one connection and closes it, whatever happened. */
	private fun serveThenClose(client: Socket) {
		try {
			handleClient(client)
		} catch (e: Exception) {
			reportClientFailure(client, e)
		} finally {
			// close() is declared to throw, and a client that reset mid-response makes it do so. That
			// exception used to leave this function -- from a finally, so it replaced any in-flight one
			// -- and unwound past the accept loop into start(), taking the listener and the database
			// down with it. "Whatever happened" includes this.
			try {
				client.close()
			} catch (e: IOException) {
				if (debugEnabled) log.debug("Cannot close the client socket; it is being discarded anyway.", e)
			}
			if (debugEnabled) log.debug("clientSocket was {}.", client)
		}
	}

	/** A client that went wrong: a disconnect is unremarkable, anything else earns a 500 if it can. */
	private fun reportClientFailure(
		client: Socket,
		e: Exception,
	) {
		if (debugEnabled) log.debug("Caught exception while handling a client.", e)

		if (e is java.net.SocketException && e.message?.contains("Closed", ignoreCase = true) == true) {
			if (debugEnabled) log.debug("Client disconnected: {}", e.message)
			return
		}
		log.error("Error handling client: {}", e.message, e)
		try {
			val output = client.outputStream

			sendError(PrintWriter(output, true), output, httpInternalServerError, "Internal Server Error 1")
		} catch (e2: Exception) {
			log.error("Error sending error response: {}", e2.message, e2)
		}
	}

	/**
	 * Starts the server, accepts client connections, and serves requests until shutdown.
	 *
	 * Opens the documentation source and binds the configured address. If startup fails,
	 * the error is logged and allocated resources are released.
	 */
	fun start() {
		TrafficStats.setThreadStatsTag(socketStatsTag)
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
				contentSource.open()
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

			acceptLoop(serverSocket)
		} catch (e: Exception) {
			log.error("WebServer stopped on an unhandled exception: {}", e.message, e)
		} finally {
			if (::serverSocket.isInitialized) {
				// Guarded for the same reason serveThenClose guards the client socket: close() is
				// declared to throw, and a throw here skipped database.close() and the traffic-stats
				// tag below, leaving the SQLite handle open for the life of the process.
				try {
					serverSocket.close()
				} catch (e: IOException) {
					log.error("Cannot close the server socket: {}", e.message, e)
				}
			}

			// The source is opened before the stopRequested check that can abort start() early (and
			// before the accept loop on every other exit path), so it has to be closed here too,
			// not just serverSocket. Closing an unopened source is a no-op, and it closes under its
			// own write lock, so a read in flight on another thread -- a WebView's, through the
			// interceptor's separate source -- finishes before any handle goes.
			contentSource.close()
			TrafficStats.clearThreadStatsTag()
		}
	}

	/**
	 * Whether an accept failure means the server is shutting down rather than having hit something
	 * transient. `ServerSocket.accept()` is declared to throw `IOException`, of which
	 * `SocketException` is one subtype, so only the listening socket closing ends the loop --
	 * everything else, a `SocketTimeoutException` or a descriptor-exhaustion `IOException` included,
	 * is retried. Getting this wrong is bad in a different way each way round: treating a transient
	 * failure as terminal stops serving documentation until the app restarts, and treating the close
	 * as transient spins the loop against a dead socket.
	 *
	 * Decided from state, not from the exception's message, which would spin forever against a
	 * platform that worded a closed socket differently. [stopRequested] is what carries the decision:
	 * libcore's [ServerSocket.close] calls `impl.close()` *before* setting its closed flag, so
	 * accept() can unblock while [ServerSocket.isClosed] is still false, and [stop] sets
	 * [stopRequested] before closing for exactly that reason. `isClosed` is the belt to that braces
	 * -- it also covers a close that did not come through [stop] at all.
	 */
	internal fun shouldStopAccepting(socket: ServerSocket): Boolean = stopRequested || socket.isClosed

	/**
	 * Waits [delayMs] before the next accept() attempt, so a persistent failure cannot spin this loop
	 * at full tilt while it clears. Only the failure path ever waits.
	 *
	 * "Too many open files" is the realistic cause, and it is not this server's own doing:
	 * [handleClient] runs inline on this thread, so exactly one client socket is ever open and the
	 * listener holds two descriptors in total. The pressure comes from the rest of the process --
	 * Gradle, Termux, the editor -- and clears on its timescale, which is why the interval escalates
	 * rather than the retries running out.
	 *
	 * Returns false if the wait was interrupted, which the caller must treat as shutdown: re-arming
	 * the flag and carrying on made every later sleep throw at once, turning the backoff into a hot
	 * spin -- the opposite of its purpose.
	 */
	private fun pauseAfterFailedAccept(delayMs: Long): Boolean =
		try {
			sleepMs(delayMs)
			true
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		}

	/**
	 * Reads an HTTP header line from the input stream.
	 *
	 * @return The line decoded as ISO-8859-1 without its line terminator, or `null` if the stream ends before any data is read.
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

	/**
	 * Parses an HTTP request and routes supported GET requests to the appropriate handler.
	 *
	 * Malformed request lines receive a 400 response, while unsupported methods receive a 501 response.
	 */
	private fun handleClient(clientSocket: Socket) {
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

		// serveRequest applies any pending sdcard debug-database swap via the content source.
		serveRequest(writer, output, path)
	}

	/**
	 * Serves a parsed request using the appropriate diagnostic endpoint or documentation content.
	 *
	 * @param writer The writer for the HTTP response.
	 * @param output The output stream for the HTTP response.
	 * @param path The normalized request path.
	 */
	private fun serveRequest(
		writer: PrintWriter,
		output: java.io.OutputStream,
		path: String,
	) {
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

		// Raw target first, percent-decoded on a miss -- the shared fallback in the content source,
		// so this transport and the in-process interceptor cannot disagree about which pages exist.
		val (queriedPath, lookup) = contentSource.lookupRequestPath(path)
		when (lookup) {
			is DocumentationLookup.Found -> {
				sendContent(writer, output, lookup.content)
			}

			is DocumentationLookup.NotFound -> {
				sendError(writer, output, httpNotFound, "Not Found")
			}

			is DocumentationLookup.Ambiguous -> {
				// queriedPath, not the raw target: it names the form the duplicate rows actually
				// match, so a bug report quotes a query that reproduces.
				sendError(
					writer,
					output,
					httpInternalServerError,
					"Corrupt database - ${lookup.rowCount} records found when unique record expected, Path queried: '$queriedPath'.",
				)
			}

			is DocumentationLookup.Failed -> {
				sendError(writer, output, httpInternalServerError, "Internal Server Error", lookup.cause.message ?: "")
			}
		}
	}

	/**
	 * Sends the supplied content as an HTTP 200 response.
	 *
	 * @param content The content and MIME type to send to the client.
	 */
	private fun sendContent(
		writer: PrintWriter,
		output: java.io.OutputStream,
		content: DocumentationContent,
	) {
		val bytes = content.bytes

		// Built before the status line goes out: the writer autoflushes, so everything after the
		// first println is already on the wire, and a throw past that point would make sendError
		// append a second status line to a response that already claimed 200 -- which a client
		// parses as a malformed header rather than as an error.
		val contentTypeHeader = ContentTypeHeaders.headerValue(content.mimeType)

		try {
			writer.println("HTTP/1.1 200 OK")
			writer.println("Content-Type: $contentTypeHeader")
			writer.println("Content-Length: ${bytes.size}")
			writer.println("Connection: close")
			writer.println()
			writer.flush()
			output.write(bytes)
			output.flush()
		} catch (e: Exception) {
			log.error("Error processing request: {}", e.message, e)
			sendError(writer, output, httpInternalServerError, "Internal Server Error", e.message ?: "", outputStarted = true)
		}
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
			html = contentSource.withDatabase { database -> lastChangeTableHtml(database) }

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
	 * Builds an HTML table containing the 20 most recent rows from the `LastChange` table.
	 *
	 * @param database The database containing the `LastChange` table.
	 * @return The generated HTML table.
	 */
	private fun lastChangeTableHtml(database: SQLiteDatabase): String {
		var html: String

		run {
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
		}

		return html
	}

	/**
	 * Generates and sends the bookshelf HTML response.
	 *
	 * Clears the relevant template caches when cache clearing is enabled. Sends an HTTP 500
	 * response if bookshelf generation fails before response output begins.
	 */
	private fun handleBsEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
	) {
		if (debugEnabled) log.debug("Entering handleBsEndpoint().")
		if (clearCacheEnabled) {
			// The in-app WebViews are served by the shared interceptor's own source, not this
			// server's, so the developer sentinel must clear both caches.
			contentSource.clearTemplateCache()
			DocumentationRequestInterceptor.clearSharedTemplateCache()
		}

		var outputStarted = false

		try {
			outputStarted = realHandleBsEndpoint(writer, output) { outputStarted = true }
		} catch (e: Exception) {
			log.error("Error handling /pr/bs endpoint: {}", e.message)
			// The message, not just the generic text: a template this endpoint cannot resolve -- the
			// bookshelf row itself, or anything it references -- is named by the loader's throw, and
			// that name is the whole diagnostic (ADFA-5405).
			sendError(
				writer,
				output,
				httpInternalServerError,
				"Internal Server Error 6",
				e.message ?: "Error generating bookshelf HTML.",
				outputStarted,
			)
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
	 * Generates the bookshelf page and sends it to the client.
	 *
	 * @return `true` if a response was produced, `false` if processing failed or no response was produced.
	 */
	private fun realHandleBsEndpoint(
		writer: PrintWriter,
		output: java.io.OutputStream,
		markOutputStarted: () -> Unit,
	): Boolean {
		if (debugEnabled) log.debug("Entering realHandleBsEndpoint().")

		// Null means an error response has already been sent, so there is nothing left to write.
		val jsonText =
			contentSource.withDatabase { database ->
				try {
					bookshelfJson(database).also {
						if (debugEnabled) log.debug("json content = '{}'.", String(it, Charsets.UTF_8))
					}
				} catch (e: Exception) {
					log.error("Error processing request: {}", e.message)
					sendError(writer, output, httpInternalServerError, "Internal Server Error", e.message ?: "")
					null
				}
			} ?: return false

		val result = contentSource.renderNamedTemplate("bookshelf", jsonText, "/bookshelf")

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
