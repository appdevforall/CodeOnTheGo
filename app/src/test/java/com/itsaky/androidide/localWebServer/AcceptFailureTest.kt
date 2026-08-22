package com.itsaky.androidide.localWebServer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * ADFA-5242: one failed accept() must not take documentation down for the rest of the session.
 *
 * `ServerSocket.accept()` is declared to throw `IOException`; `SocketException` is one subtype. The
 * loop used to catch only that subtype, and the enclosing try has a finally but no catch, so any
 * other `IOException` unwound to `start()`'s outermost handler -- whose finally closes the listening
 * socket *and* the database. Every later request then failed until the app restarted, with one log
 * line as the only trace.
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
	fun `only the listening socket closing stops the accept loop`() {
		assertThat(server().shouldStopAccepting(SocketException("Socket closed"))).isTrue()
		assertThat(server().shouldStopAccepting(SocketException("socket is CLOSED"))).isTrue()
	}

	@Test
	fun `a transient accept failure is retried, whatever its type`() {
		assertThat(server().shouldStopAccepting(SocketException("Connection reset by peer"))).isFalse()
		assertThat(server().shouldStopAccepting(SocketTimeoutException("Accept timed out"))).isFalse()
		assertThat(server().shouldStopAccepting(IOException("Too many open files"))).isFalse()
	}

	// A message-less exception must not be mistaken for the close, whose only marker is its message.
	@Test
	fun `an accept failure with no message is retried`() {
		assertThat(server().shouldStopAccepting(IOException())).isFalse()
		assertThat(server().shouldStopAccepting(SocketException())).isFalse()
	}

	// The helpers above are only worth having if the loop calls them. It did not: both were dead
	// code reachable from tests alone, so the fix this PR claimed to make did not exist. These two
	// drive the real loop instead of the predicate.
	@Test
	fun `a retryable failure does not end the accept loop`() {
		val socket = ScriptedServerSocket(failures = 2)
		socket.use { server().acceptLoop(it) }

		// Three: two failures retried, then the close that ends it. One would mean the first
		// failure escaped the loop -- the ADFA-5242 bug.
		assertThat(socket.acceptCalls).isEqualTo(3)
	}

	@Test
	fun `a closed socket ends the accept loop at once`() {
		val socket = ScriptedServerSocket(failures = 0)
		socket.use { server().acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(1)
	}

	// stop() is authoritative where the exception text is not. If a platform words a closed socket
	// differently, matching on the message alone would spin here until the cap instead of exiting,
	// leaving start()'s finally unrun -- the database open and the port held.
	@Test
	fun `a requested stop ends the loop whatever the exception says`() {
		val server = server()
		// No socket is bound yet, so this only records that a stop was asked for.
		server.stop()

		val socket = ScriptedServerSocket(failures = Int.MAX_VALUE, message = "unexpected wording")
		socket.use { server.acceptLoop(it) }

		assertThat(socket.acceptCalls).isEqualTo(1)
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

	/** Fails accept() [failures] times with a retryable error, then reports the socket closed. */
	private class ScriptedServerSocket(
		private val failures: Int,
		private val succeedAt: Set<Int> = emptySet(),
		private val message: String = "Too many open files",
	) : ServerSocket() {
		var acceptCalls = 0
			private set

		override fun accept(): Socket {
			acceptCalls++
			// A returned socket would send the loop into handleClient, which needs a database; the
			// loop only reads it for null, so an unconnected one is enough to count as a success.
			if (acceptCalls in succeedAt) return Socket()
			if (acceptCalls <= failures) throw IOException(message)
			throw SocketException("Socket closed")
		}
	}
}
