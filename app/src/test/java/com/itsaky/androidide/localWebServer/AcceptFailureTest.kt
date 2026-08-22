package com.itsaky.androidide.localWebServer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
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
}
