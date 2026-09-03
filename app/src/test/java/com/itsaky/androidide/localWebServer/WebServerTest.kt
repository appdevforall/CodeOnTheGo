package com.itsaky.androidide.localWebServer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.TrafficStats
import com.itsaky.androidide.utils.DatabaseVersionResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

// Covers the ADFA-5035 fix: start()'s bind and stop()'s close are serialized on a
// shared lock, with a stopRequested flag, so no ordering of the two calls can leave
// serverSocket bound-but-orphaned. The first test needs no real concurrency at all
// (stop() fully happens-before start()); the second uses a bounded, connect-based
// poll as the readiness signal instead of a fixed sleep.
class WebServerTest {
	@Before
	fun setup() {
		mockkStatic(TrafficStats::class)
		every { TrafficStats.setThreadStatsTag(any()) } returns Unit
		every { TrafficStats.clearThreadStatsTag() } returns Unit

		// start() opens config.databasePath before ever reaching the bind step;
		// stub it out since these tests exercise the bind/stop lifecycle, not the
		// HTTP-serving behavior that depends on real database content.
		mockkStatic(SQLiteDatabase::class)
		every {
			SQLiteDatabase.openDatabase(any(), isNull(), any())
		} returns mockk<SQLiteDatabase>(relaxed = true)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun testConfig(port: Int) = testServerConfig(port)

	// ADFA-5153/ADFA-5220: DocumentationContentSource gates the dictionary on the MAJOR version the
	// database declares, so a test expecting the dictionary to load has to declare one. A relaxed
	// mock answers the existence probe with moveToFirst() = false -- "no version table" -- which
	// would quietly turn these tests into no-ops instead of failing them.
	private fun stubDeclaredMajorVersion(
		db: SQLiteDatabase,
		major: Int = DatabaseVersionResolver.MAJOR_VERSION_WITH_COMPRESSION_DICTIONARY,
	) {
		every {
			db.rawQuery(match { it.contains("FROM   sqlite_master") && it.contains("DocumentationDatabaseVersion") }, any())
		} returns mockk<Cursor>(relaxed = true) { every { moveToFirst() } returns true }
		every {
			db.rawQuery(match { it.contains("FROM   DocumentationDatabaseVersion") }, any())
		} returns
			mockk<Cursor>(relaxed = true) {
				every { moveToFirst() } returns true
				every { isNull(0) } returns false
				every { getInt(0) } returns major
				// The row count the query carries. Left unstubbed, a relaxed mock answers 0 -- a
				// state the production code has just excluded by getting a row back at all, so
				// these tests would be exercising something that cannot happen.
				every { getInt(1) } returns 1
			}
	}

	private fun freePort(): Int = ServerSocket(0).use { it.localPort }

	private fun assertPortIsFree(port: Int) {
		ServerSocket().apply { reuseAddress = true }.use { probe ->
			probe.bind(InetSocketAddress("localhost", port))
			assertTrue("Expected to rebind port $port", probe.isBound)
		}
	}

	@Test
	fun `stop before start prevents the socket from ever binding`() {
		val port = freePort()
		val server = WebServer(testConfig(port))

		server.stop()
		// stopRequested is now true, so start() must abort inside its synchronized
		// bind block without ever calling ServerSocket.bind(). Run it on a joined,
		// bounded-timeout thread rather than calling it inline: if this fix ever
		// regresses, start() binds anyway and blocks forever in its accept loop,
		// and an inline call would hang this test (and the whole test JVM) instead
		// of failing it.
		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		serverThread.join(2_000)
		assertFalse("Expected start() to return once stop() had already been requested", serverThread.isAlive)

		// If start() had bound anyway, this second bind on the same port would
		// throw BindException ("Address already in use").
		assertPortIsFree(port)
	}

	@Test
	fun `start then stop closes the socket so the port can be reused`() {
		val port = freePort()
		val server = WebServer(testConfig(port))

		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		try {
			awaitPortBound(port)
		} finally {
			server.stop()
			serverThread.join(2_000)
		}

		assertPortIsFree(port)
	}

	// ADFA-5153: the compression dictionary is loaded lazily -- not merely from starting the
	// server -- but only once per database, cached across every subsequent request against that
	// same database rather than re-fetched per-request.
	@Test
	fun `compression dictionary loads lazily on first use, once per database, not once per request`() {
		val port = freePort()

		val dictionaryExistsCursor =
			mockk<Cursor>(relaxed = true) {
				every { moveToFirst() } returns true
			}
		val dictionaryDataCursor =
			mockk<Cursor>(relaxed = true) {
				every { moveToFirst() } returns true
				every { getBlob(0) } returns "test-dictionary-bytes".toByteArray()
			}
		val contentCursor =
			mockk<Cursor>(relaxed = true) {
				every { count } returns 1
				every { moveToFirst() } returns true
				every { getBlob(0) } returns "hello".toByteArray()
				every { getString(1) } returns "text/plain"
				every { getString(2) } returns "none"
				every { getInt(3) } returns 0
			}

		val db = mockk<SQLiteDatabase>(relaxed = true)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns db
		stubDeclaredMajorVersion(db)
		every {
			db.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
		} returns dictionaryExistsCursor
		every {
			db.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
		} returns dictionaryDataCursor
		every {
			db.rawQuery(match { it.contains("FROM   Content") }, any())
		} returns contentCursor

		val server = WebServer(testConfig(port))
		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		try {
			awaitPortBound(port)

			// Nothing fetches the dictionary merely from starting the server -- only a content
			// fetch does, so before any request there should be no dictionary query at all yet --
			// neither the sqlite_master existence check nor the data fetch.
			verify(exactly = 0) {
				db.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			}
			verify(exactly = 0) {
				db.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
			}

			repeat(3) { sendRawGetRequestAndAwaitClose(port, "/some/path") }

			// Exactly one dictionary load across all 3 requests against the same, unchanged
			// database -- the first request's lazy load, cached for the other two. Both queries
			// loadCompressionDictionary issues (the sqlite_master existence check, then the data
			// fetch) must be checked, or a regression re-running just the existence check on
			// every request would pass unnoticed.
			verify(exactly = 1) {
				db.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			}
			verify(exactly = 1) {
				db.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
			}
		} finally {
			server.stop()
			serverThread.join(2_000)
		}
	}

	// ADFA-5153: a database swap (the debug-DB override) must invalidate the cached dictionary --
	// the new database can have a different one, or none -- causing exactly one fresh reload on
	// the first content fetch against the new database, not a reload on every later request too.
	@Test
	fun `database swap invalidates the cached dictionary, reloading it once for the new database`() {
		val port = freePort()
		val debugDbFile = File.createTempFile("webserver-test-debug", ".db")
		debugDbFile.delete() // must not exist yet -- the first request should stay on the primary db

		fun contentCursorFor(marker: String) =
			mockk<Cursor>(relaxed = true) {
				every { count } returns 1
				every { moveToFirst() } returns true
				every { getBlob(0) } returns marker.toByteArray()
				every { getString(1) } returns "text/plain"
				every { getString(2) } returns "none"
				every { getInt(3) } returns 0
			}

		fun stubDatabase(
			db: SQLiteDatabase,
			dictionaryBytes: String,
		) {
			stubDeclaredMajorVersion(db)
			every {
				db.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			} returns mockk<Cursor>(relaxed = true) { every { moveToFirst() } returns true }
			every {
				db.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
			} returns
				mockk<Cursor>(relaxed = true) {
					every { moveToFirst() } returns true
					every { getBlob(0) } returns dictionaryBytes.toByteArray()
				}
			every {
				db.rawQuery(match { it.contains("FROM   Content") }, any())
			} returns contentCursorFor(dictionaryBytes)
		}

		val primaryDb = mockk<SQLiteDatabase>(relaxed = true)
		val debugDb = mockk<SQLiteDatabase>(relaxed = true)
		stubDatabase(primaryDb, "dict-primary")
		stubDatabase(debugDb, "dict-debug")

		// ADFA-5175 rate-limits the debug-database stat to once a second; this test drops a newer
		// file and expects the very next request to see it, so it opts out of the rate limit.
		val config =
			testConfig(port).copy(
				debugDatabasePath = debugDbFile.absolutePath,
				debugDatabaseCheckIntervalMs = 0,
			)
		every { SQLiteDatabase.openDatabase(config.databasePath, isNull(), any()) } returns primaryDb
		every { SQLiteDatabase.openDatabase(config.debugDatabasePath, isNull(), any()) } returns debugDb

		val server = WebServer(config)
		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		try {
			awaitPortBound(port)

			sendRawGetRequestAndAwaitClose(port, "/some/path")
			verify(exactly = 1) {
				primaryDb.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			}
			verify(exactly = 1) {
				primaryDb.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
			}
			verify(exactly = 0) {
				debugDb.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			}
			verify(exactly = 0) {
				debugDb.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
			}

			// Now make the debug override newer than the primary database -- the swap check in
			// handleClient() picks this up on the very next request.
			debugDbFile.createNewFile()
			debugDbFile.setLastModified(System.currentTimeMillis() + 60_000)

			repeat(2) { sendRawGetRequestAndAwaitClose(port, "/some/path") }

			// Exactly one reload for the new (debug) database, across both post-swap requests --
			// not zero (it must invalidate), not two (it must still cache after the first reload).
			// Both queries loadCompressionDictionary issues must be checked (see the sibling test).
			verify(exactly = 1) {
				debugDb.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			}
			verify(exactly = 1) {
				debugDb.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
			}
			// The primary database's dictionary is never touched again after the swap.
			verify(exactly = 1) {
				primaryDb.rawQuery(match { it.contains("FROM sqlite_master") && it.contains("CompressionDictionary") }, null)
			}
			verify(exactly = 1) {
				primaryDb.rawQuery(match { it.contains("SELECT data FROM CompressionDictionary") }, null)
			}
		} finally {
			server.stop()
			serverThread.join(2_000)
			debugDbFile.delete()
		}
	}

	// Stored Content.path rows are percent-encoded, so the raw target is what matches them and is
	// queried first; the decoded form is the fallback on a miss. The lookup order lives in
	// DocumentationContentSource, shared with the in-process transport, so the nointercept sentinel
	// compares the two on equal terms.
	@Test
	fun `a percent-encoded request path is looked up verbatim first, then decoded on a miss`() {
		assertLookedUpPaths(requested = "/a/my%20file.html", expected = listOf("a/my%20file.html", "a/my file.html"))
	}

	// URLDecoder turns "+" into a space; a stored path containing a literal plus must survive --
	// and the protected decode is then the identity, so there is no fallback to query.
	@Test
	fun `a plus in a request path stays a plus`() {
		assertLookedUpPaths(requested = "/a/c++.html", expected = listOf("a/c++.html"))
	}

	// A malformed escape is not a reason to fail the request: look it up verbatim and 404 naturally.
	@Test
	fun `a malformed escape is looked up verbatim rather than failing the request`() {
		assertLookedUpPaths(requested = "/a/%zz.html", expected = listOf("a/%zz.html"))
	}

	private fun assertLookedUpPaths(
		requested: String,
		expected: List<String>,
	) {
		val port = freePort()
		val db = mockk<SQLiteDatabase>(relaxed = true)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns db
		val queried = mutableListOf<Array<String>>()
		every { db.rawQuery(match { it.contains("FROM   Content") }, any()) } answers
			{
				@Suppress("UNCHECKED_CAST")
				(secondArg<Array<String>?>())?.let { queried += it as Array<String> }
				mockk<Cursor>(relaxed = true) { every { count } returns 0 }
			}

		val server = WebServer(testConfig(port))
		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		try {
			awaitPortBound(port)
			sendRawGetRequestAndAwaitClose(port, requested)
			assertEquals(expected, queried.map { it.first() })
		} finally {
			server.stop()
			serverThread.join(2_000)
		}
	}

	// ADFA-5179 rewrote the bookshelf query: the old JSON1/group_concat version turned an empty join
	// into a 500 (group_concat over no rows is NULL, and reading that as a blob threw). The contract
	// now is a normal page -- 200, rendered from the empty-shelf {"result":[]} payload. The sibling
	// Bookshelf*Tests pin readBookshelf/bookshelfJson directly; this one proves the HTTP layer, where
	// the blank-context render guard sits outside realHandleBsEndpoint's try/catch and would 500.
	@Test
	fun `an empty bookshelf join answers 200 with an empty shelf`() {
		val port = freePort()
		val db = mockk<SQLiteDatabase>(relaxed = true)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns db
		// The bookshelf join matches nothing: a cursor whose moveToNext() is immediately false.
		every { db.rawQuery(match { it.contains("FROM Content AS C") }, any()) } returns
			mockk<Cursor>(relaxed = true) { every { moveToNext() } returns false }
		// The bookshelf template's body, fetched by name (ADFA-5405) -- a Pebble expression over the
		// JSON context, so the assertion proves the empty-shelf payload actually reached the render.
		every { db.rawQuery(match { it.contains("FROM Templates WHERE name") }, any()) } returns
			mockk<Cursor>(relaxed = true) {
				every { count } returns 1
				every { moveToFirst() } returns true
				every { getBlob(0) } returns "shelf:{{ result | length }}".toByteArray()
			}

		val server = WebServer(testConfig(port))
		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		try {
			awaitPortBound(port)
			val response = sendRawGetRequest(port, "/pr/bs")
			assertTrue("Expected a 200 status line, got:\n$response", response.startsWith("HTTP/1.1 200"))
			assertTrue("Expected the empty shelf to render, got:\n$response", response.endsWith("shelf:0"))
		} finally {
			server.stop()
			serverThread.join(2_000)
		}
	}

	// ADFA-5405: a template the endpoint cannot resolve -- the bookshelf row, or anything it
	// references -- is named by the loader's throw, and handleBsEndpoint has to pass that name on
	// rather than replace it with its generic text. The name is the whole diagnostic.
	@Test
	fun `a bookshelf template that is not in the database answers 500 naming it`() {
		val port = freePort()
		val db = mockk<SQLiteDatabase>(relaxed = true)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns db
		every { db.rawQuery(match { it.contains("FROM Content AS C") }, any()) } returns
			mockk<Cursor>(relaxed = true) { every { moveToNext() } returns false }
		// No bookshelf row: a relaxed cursor's moveToFirst() is already false.
		every { db.rawQuery(match { it.contains("FROM Templates WHERE name") }, any()) } returns mockk<Cursor>(relaxed = true)

		val server = WebServer(testConfig(port))
		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		try {
			awaitPortBound(port)
			val response = sendRawGetRequest(port, "/pr/bs")
			assertTrue("Expected a 500 status line, got:\n$response", response.startsWith("HTTP/1.1 500"))
			assertTrue("Expected the missing template to be named, got:\n$response", response.contains("bookshelf"))
		} finally {
			server.stop()
			serverThread.join(2_000)
		}
	}

	// ADFA-5241: the two transports have to answer the same way about what a response says, and
	// only a real response proves what this one sends. The decision itself lives in
	// ContentTypeHeaders, shared with DocumentationRequestInterceptor.
	@Test
	fun `a text response declares utf-8 and a binary one does not`() {
		assertContentTypeHeader(storedMimeType = "text/html", expected = "text/html; charset=utf-8")
		assertContentTypeHeader(storedMimeType = "image/png", expected = "image/png")
	}

	private fun assertContentTypeHeader(
		storedMimeType: String,
		expected: String,
	) {
		val port = freePort()
		val db = mockk<SQLiteDatabase>(relaxed = true)
		every { SQLiteDatabase.openDatabase(any(), isNull(), any()) } returns db
		// Deliberately no version stub: this row is compression = "none", so nothing decodes and no
		// dictionary is consulted. Declaring one would couple the assertion to a lazy-load path it
		// does not exercise.
		every {
			db.rawQuery(match { it.contains("FROM   Content") }, any())
		} returns
			mockk<Cursor>(relaxed = true) {
				every { count } returns 1
				every { moveToFirst() } returns true
				every { getBlob(0) } returns "payload".toByteArray()
				every { getString(1) } returns storedMimeType
				every { getString(2) } returns "none"
				every { getInt(3) } returns 0
			}

		val server = WebServer(testConfig(port))
		val serverThread = Thread { server.start() }.apply { isDaemon = true }
		serverThread.start()
		try {
			awaitPortBound(port)
			val response = sendRawGetRequest(port, "/some/path")
			val header =
				response.lineSequence().firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
					?: error("No Content-Type in the response:\n$response")
			assertEquals("Content-Type: $expected", header.trim())
		} finally {
			server.stop()
			serverThread.join(2_000)
		}
	}

	// Sends a bare GET over a raw socket and hands back everything the server wrote, reading
	// until the server closes the connection (every response sends "Connection: close").
	// Plaintext HTTP is intentional and stays on this machine: WebServer is a loopback-only
	// plaintext server, and these tests exercise it as shipped.
	private fun sendRawGetRequest(
		port: Int,
		path: String,
	): String =
		Socket().use { socket ->
			socket.connect(InetSocketAddress("localhost", port), 2_000)
			socket.soTimeout = 2_000
			socket.getOutputStream().apply {
				write("GET $path HTTP/1.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
				flush()
			}
			socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
		}

	// Discards the response; because sendRawGetRequest reads until the server closes the
	// connection, by the time this returns the server has fully finished processing this one
	// request -- making repeated calls a reliable way to serialize several full request/response
	// cycles.
	private fun sendRawGetRequestAndAwaitClose(
		port: Int,
		path: String,
	) {
		sendRawGetRequest(port, path)
	}

	// Polls by attempting an actual TCP connect rather than sleeping a fixed
	// duration: as soon as WebServer's accept() loop is listening, the connect
	// succeeds, which is the readiness signal. (A bind-then-unbind probe was
	// tried first and was itself racy against the server's own bind.) The small
	// sleep between attempts matters -- a bare spin loop can starve the JVM's
	// other threads, including the one running WebServer.start(), of a chance to
	// run at all on a constrained number of cores.
	private fun awaitPortBound(port: Int) {
		val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (System.nanoTime() < deadlineNanos) {
			try {
				Socket().use { it.connect(InetSocketAddress("localhost", port), 200) }
				return
			} catch (_: Exception) {
				Thread.sleep(10)
			}
		}
		error("WebServer did not bind port $port in time")
	}
}
