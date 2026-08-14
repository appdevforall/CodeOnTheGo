package com.itsaky.androidide.localWebServer

import android.database.sqlite.SQLiteDatabase
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

		// SqliteMmapConfigurator.configureMmap(), called right after openDatabase, uses
		// Process.is64Bit() and Log.i()/Log.w() (the relaxed db mock's rawQuery() returns
		// a cursor whose moveToFirst() defaults to false, taking the Log.w "not enabled"
		// branch); none of that is mocked by Android's unit-test stubs like SQLiteDatabase
		// is, so all three throw unless stubbed here.
		mockkStatic(Process::class)
		every { Process.is64Bit() } returns true

		mockkStatic(Log::class)
		every { Log.i(any(), any()) } returns 0
		every { Log.w(any(), any<String>()) } returns 0
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun testConfig(port: Int) =
		ServerConfig(
			port = port,
			databasePath = "/nonexistent/test.db",
			fileDirPath = "/tmp",
			debugDatabasePath = "/nonexistent/debug.db",
			debugEnablePath = "/nonexistent/debug-flag",
			experimentsEnablePath = "/nonexistent/exp-flag",
			clearCacheEnablePath = "/nonexistent/cs0-flag",
			projectDatabasePath = "/nonexistent/recent-projects.db",
		)

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
