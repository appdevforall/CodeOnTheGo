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
	private fun server() =
		WebServer(
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
		val socket = ScriptedServerSocket(failures = Int.MAX_VALUE, error = { SocketException("Socket closed") })
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(20)
	}

	// Every IOException subtype is retried, not just the ones named in the original bug report: a
	// SocketTimeoutException reaching start()'s handler would have killed the server just as surely.
	@Test
	fun `a transient failure is retried whatever its type`() {
		val socket =
			ScriptedServerSocket(failures = Int.MAX_VALUE, error = { java.net.SocketTimeoutException("Accept timed out") })
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(20)
	}

	// A backoff bounds CPU, not volume: without a cap this loop retries a permanent failure forever,
	// logging every 50 ms. Giving up leaves a listener that was not serving anyway, and says so once.
	@Test
	fun `a permanent failure is abandoned rather than retried forever`() {
		val socket = ScriptedServerSocket(failures = Int.MAX_VALUE)
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(20)
	}

	// A successful accept means the condition cleared, so the count towards the cap starts over --
	// otherwise a server up for long enough accumulates unrelated failures and stops on the twentieth.
	@Test
	fun `a success between failures resets the count`() {
		val socket = ScriptedServerSocket(failures = Int.MAX_VALUE, succeedAt = setOf(5, 10))
		socket.use { server().acceptLoop(it) }

		// 4 failures, a success, 4 more, a success, then a full run of 20 to the cap.
		assertThat(socket.acceptCalls).isEqualTo(30)
	}

	/**
	 * Fails accept() [failures] times, then closes itself and reports it -- the order a real
	 * `ServerSocket` uses, since [ServerSocket.close] sets the closed flag before accept() unblocks.
	 */
	private class ScriptedServerSocket(
		private val failures: Int,
		private val succeedAt: Set<Int> = emptySet(),
		private val error: () -> IOException = { IOException("Too many open files") },
	) : ServerSocket() {
		var acceptCalls = 0
			private set

		override fun accept(): Socket {
			acceptCalls++
			// A returned socket would send the loop into handleClient, which needs a database; the
			// loop only needs one to hand on, so an unconnected one counts as a success.
			if (acceptCalls in succeedAt) return Socket()
			if (acceptCalls <= failures) throw error()
			close()
			throw SocketException("Socket closed")
		}
	}
}
