package com.itsaky.androidide.localWebServer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * ADFA-5242: one failed accept() must not take documentation down for the rest of the session.
 *
 * `ServerSocket.accept()` is declared to throw `IOException`; `SocketException` is one subtype. The
 * loop used to catch only that subtype, and the enclosing try had a finally but no catch, so any
 * other `IOException` -- a descriptor-exhaustion "Too many open files", say -- unwound to
 * `start()`'s outermost handler, whose finally closes the listening socket *and* the database. Every
 * later request then failed until the app restarted, with one log line as the only trace.
 *
 * These drive the real loop through a socket whose accept() fails on demand. Stopping is decided
 * from socket state rather than the exception's message, so the scripted socket closes itself when it
 * means "stop" -- which is what a real `ServerSocket` does before accept() unblocks.
 */
class AcceptFailureTest {
	// Every path is given explicitly: ServerConfig's defaults reach for external storage, which a
	// JVM test has no stub for.
	// Every delay the loop asked for, in order. Recording instead of sleeping keeps a test that drives
	// hundreds of failures instant, and makes the backoff itself assertable.
	private val delays = mutableListOf<Long>()

	private fun server(onSleep: (Long) -> Unit = {}) =
		WebServer(
			sleepMs = {
				delays += it
				onSleep(it)
			},
			config =
				ServerConfig(
					port = 0,
					databasePath = "/nonexistent/test.db",
					fileDirPath = "/tmp",
					debugDatabasePath = "/nonexistent/debug.db",
					debugEnablePath = "/nonexistent/debug-flag",
					experimentsEnablePath = "/nonexistent/exp-flag",
					clearCacheEnablePath = "/nonexistent/cs0-flag",
					projectDatabasePath = "/nonexistent/recent-projects.db",
				),
		)

	@Test
	fun `a closed socket is what stops accepting`() {
		val server = server()
		ServerSocket().use { open ->
			assertThat(server.shouldStopAccepting(open)).isFalse()
			open.close()
			assertThat(server.shouldStopAccepting(open)).isTrue()
		}
	}

	// stop() can arrive before start() has bound anything, so it records the intent and the loop has
	// to honour it even while the socket it was handed is still open.
	@Test
	fun `a requested stop is honoured before the socket closes`() {
		val server = server()
		server.stop()

		ServerSocket().use { open ->
			assertThat(server.shouldStopAccepting(open)).isTrue()
		}
	}

	@Test
	fun `a closed socket ends the accept loop at once`() {
		val socket = ScriptedServerSocket(failures = 0)
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(1)
	}

	@Test
	fun `a retryable failure does not end the accept loop`() {
		val socket = ScriptedServerSocket(failures = 2)
		socket.use { server().acceptLoop(it) }

		// Three: two failures retried, then the close that ends it. One would mean the first failure
		// escaped the loop -- the ADFA-5242 bug.
		assertThat(socket.acceptCalls).isEqualTo(3)
	}

	// A message is not evidence. An exception *saying* the socket closed, from a socket that is still
	// open, is some other fault and gets retried like any other.
	@Test
	fun `a closed-sounding message from a live socket is retried`() {
		val socket = ScriptedServerSocket(failures = 30, error = { SocketException("Socket closed") })
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(31)
	}

	// Every IOException subtype is retried, not just the ones named in the original bug report: a
	// SocketTimeoutException reaching start()'s handler would have killed the server just as surely.
	@Test
	fun `a transient failure is retried whatever its type`() {
		val socket =
			ScriptedServerSocket(failures = 5, error = { java.net.SocketTimeoutException("Accept timed out") })
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(6)
	}

	// The loop used to give up after twenty consecutive failures, which returned to start(), whose
	// finally closes the listening socket *and* the database -- documentation dead for the rest of the
	// process. That is the ADFA-5242 symptom the retry exists to prevent, so there is no giving up:
	// only the socket closing ends the loop.
	@Test
	fun `a persistent failure is retried past any cap, until the socket closes`() {
		val socket = ScriptedServerSocket(failures = 500)
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(501)
	}

	// What bounds the cost of a persistent failure is the interval, not a cap on attempts.
	@Test
	fun `the retry interval doubles from 50 ms and stops at 2 seconds`() {
		val socket = ScriptedServerSocket(failures = 20)
		socket.use { server().acceptLoop(it) }

		assertThat(delays.take(7)).containsExactly(50L, 100L, 200L, 400L, 800L, 1600L, 2000L).inOrder()
		assertThat(delays.distinct().max()).isEqualTo(2000L)
	}

	// A successful accept means the condition cleared, so the next failure starts over at 50 ms rather
	// than inheriting an interval the server has already recovered from.
	@Test
	fun `a success resets the retry interval`() {
		val socket = ScriptedServerSocket(failures = 12, succeedAt = setOf(5))
		socket.use { server().acceptLoop(it) }

		// Four failures before the success, so the fifth delay is the one after it.
		assertThat(delays.take(4)).containsExactly(50L, 100L, 200L, 400L).inOrder()
		assertThat(delays[4]).isEqualTo(50L)
	}

	// Re-arming the interrupt flag and carrying on made every later sleep throw immediately, so the
	// backoff became a hot spin -- the opposite of its purpose. An interrupt ends the loop.
	@Test
	fun `an interrupt ends the accept loop instead of spinning`() {
		val socket = ScriptedServerSocket(failures = 500)
		socket.use { server(onSleep = { throw InterruptedException("shutting down") }).acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(1)
		assertThat(Thread.interrupted()).isTrue()
	}

	// Socket.close() is declared to throw and a reset client makes it do so. It ran in a finally, so
	// the exception replaced whatever was in flight and unwound past this loop into start(), whose
	// own finally closes the listener and the database -- one bad client killing documentation for
	// the session, by the same route as the accept failure this ticket is about.
	@Test
	fun `a client whose close fails does not end the accept loop`() {
		val socket =
			ScriptedServerSocket(
				failures = 1,
				succeedAt = setOf(1),
				client = {
					object : Socket() {
						override fun close() = throw IOException("Broken pipe")
					}
				},
			)
		socket.use { server().acceptLoop(it) }

		// Two: the client whose close() threw, then the close that ends the loop. One would mean the
		// throw escaped.
		assertThat(socket.acceptCalls).isEqualTo(2)
	}

	/**
	 * Fails accept() [failures] times, then closes itself and reports it.
	 *
	 * Closing before throwing is what makes [WebServer.shouldStopAccepting]'s `isClosed` arm fire
	 * here. A real libcore [ServerSocket.close] is the other way round -- `impl.close()` runs before
	 * the closed flag is set, so accept() can unblock while `isClosed()` is still false -- which is
	 * why the production path leans on `stopRequested`, set by [WebServer.stop] before it closes.
	 */
	private class ScriptedServerSocket(
		private val failures: Int,
		private val succeedAt: Set<Int> = emptySet(),
		private val error: () -> IOException = { IOException("Too many open files") },
		private val client: () -> Socket = { Socket() },
	) : ServerSocket() {
		var acceptCalls = 0
			private set

		override fun accept(): Socket {
			acceptCalls++
			// A returned socket would send the loop into handleClient, which needs a database; the
			// loop only needs one to hand on, so an unconnected one counts as a success.
			if (acceptCalls in succeedAt) return client()
			if (acceptCalls <= failures) throw error()
			close()
			throw SocketException("Socket closed")
		}
	}
}
