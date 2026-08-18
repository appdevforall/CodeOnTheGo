package com.itsaky.androidide.localWebServer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Covers when a wait in accept() is worth a log line (ADFA-5172). Waiting is the normal state of a
 * server with nothing to do, so the interesting case is narrow: long enough to be a retransmitted
 * handshake, short enough not to be an idle server, and arriving in the middle of steady traffic.
 */
class AcceptWaitReportingTest {
	private val thresholdMs = 200L

	// Every path is given explicitly: ServerConfig's defaults reach for external storage, which a
	// JVM test has no stub for.
	private fun server(stallThresholdMs: Long = thresholdMs) =
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
				stallThresholdMs = stallThresholdMs,
			),
		)

	private fun millis(value: Long) = TimeUnit.MILLISECONDS.toNanos(value)

	private val noPreviousIteration = -1L

	@Test
	fun `a one second wait between promptly served requests is the stall being hunted`() {
		assertThat(server().shouldReportAcceptWait(millis(1_020), previousAcceptWaitNanos = millis(0))).isTrue()
	}

	@Test
	fun `the first request after startup is never reported, however long the wait`() {
		assertThat(server().shouldReportAcceptWait(millis(74_000), noPreviousIteration)).isFalse()
		assertThat(server().shouldReportAcceptWait(millis(1_020), noPreviousIteration)).isFalse()
	}

	@Test
	fun `a wait after an already long wait is an idle server, not a stall`() {
		assertThat(server().shouldReportAcceptWait(millis(1_020), previousAcceptWaitNanos = millis(30_000))).isFalse()
	}

	@Test
	fun `a wait far past the retransmission ladder is nobody browsing`() {
		assertThat(server().shouldReportAcceptWait(millis(60_000), previousAcceptWaitNanos = millis(5))).isFalse()
	}

	@Test
	fun `a wait within the retransmission ladder is still reported`() {
		// 1 s, 3 s and 7 s are Linux's first three SYN retransmissions.
		assertThat(server().shouldReportAcceptWait(millis(3_100), previousAcceptWaitNanos = millis(5))).isTrue()
		assertThat(server().shouldReportAcceptWait(millis(7_200), previousAcceptWaitNanos = millis(5))).isTrue()
	}

	@Test
	fun `an ordinary wait below the threshold is not reported`() {
		assertThat(server().shouldReportAcceptWait(millis(30), previousAcceptWaitNanos = millis(5))).isFalse()
	}

	@Test
	fun `a zero or negative threshold silences the accept-wait report instead of flooding it`() {
		// "The previous iteration waited less than the threshold" cannot hold when the threshold is
		// zero, so this branch goes quiet rather than reporting every wait. A negative threshold is
		// clamped to zero and behaves the same. The busy-phase report, which has no such
		// precondition, does fire for every iteration at these settings.
		assertThat(server(stallThresholdMs = 0).shouldReportAcceptWait(millis(1_020), previousAcceptWaitNanos = 0)).isFalse()
		assertThat(server(stallThresholdMs = -5).shouldReportAcceptWait(millis(1_020), previousAcceptWaitNanos = 0)).isFalse()
	}
}
