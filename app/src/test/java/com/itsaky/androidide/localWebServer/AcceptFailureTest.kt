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

	/** Fails accept() [failures] times with a retryable error, then reports the socket closed. */
	private class ScriptedServerSocket(
		private val failures: Int,
	) : ServerSocket() {
		var acceptCalls = 0
			private set

		override fun accept(): Socket {
			acceptCalls++
			if (acceptCalls <= failures) throw IOException("Too many open files")
			throw SocketException("Socket closed")
		}
	}
}
