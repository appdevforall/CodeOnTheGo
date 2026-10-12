package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import com.termux.terminal.TerminalSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class TerminalCommandRequestsTest {
	private val exits = mutableListOf<Pair<Int, String>>()
	private val refusals = mutableListOf<String>()

	private fun enqueue(): String =
		TerminalCommandRequests.enqueue(
			command = "./gradlew --version",
			workingDirectory = "/project",
			sessionName = "test.plugin",
			onExit = { code, transcript -> exits += code to transcript },
			onNotStarted = { refusals += it },
		)

	private fun session(
		exitStatus: Int = 0,
		transcript: String = "",
	): TerminalSession =
		mockk(relaxed = true) {
			every { this@mockk.exitStatus } returns exitStatus
			every { emulator.screen.transcriptTextWithFullLinesJoined } returns transcript
		}

	@Test
	fun claimIsOneShot() {
		val id = enqueue()

		assertThat(TerminalCommandRequests.claim(id)?.command).isEqualTo("./gradlew --version")
		assertThat(TerminalCommandRequests.claim(id)).isNull()
	}

	@Test
	fun finishedSessionReportsExitCodeAndTranscriptWithoutTheBanner() {
		val request = TerminalCommandRequests.claim(enqueue())!!
		val session = session(exitStatus = 1, transcript = "$ false\n\n[Process completed (code 1) - press Enter]")
		TerminalCommandRequests.attach(request, session)

		assertThat(TerminalCommandRequests.onSessionFinished(session)).isTrue()
		assertThat(exits).containsExactly(1 to "$ false")
		// Reported once; a second finish is not a plugin session any more.
		assertThat(TerminalCommandRequests.onSessionFinished(session)).isFalse()
	}

	@Test
	fun unrelatedSessionIsNotClaimed() {
		assertThat(TerminalCommandRequests.onSessionFinished(session())).isFalse()
		assertThat(exits).isEmpty()
	}

	@Test
	fun cancelBeforeClaimMeansItNeverRuns() {
		val id = enqueue()

		TerminalCommandRequests.cancel(id)

		assertThat(TerminalCommandRequests.claim(id)).isNull()
	}

	@Test
	fun cancelBetweenClaimAndAttachKillsTheSessionOnAttach() {
		val id = enqueue()
		val request = TerminalCommandRequests.claim(id)!!
		TerminalCommandRequests.cancel(id)
		val session = session()

		TerminalCommandRequests.attach(request, session)

		verify { session.finishIfRunning() }
	}

	@Test
	fun cancelAfterAttachKillsTheSession() {
		val id = enqueue()
		val session = session()
		TerminalCommandRequests.attach(TerminalCommandRequests.claim(id)!!, session)

		TerminalCommandRequests.cancel(id)

		verify { session.finishIfRunning() }
	}

	@Test
	fun withdrawOnlyTakesBackAnUnclaimedRequest() {
		val claimed = enqueue()
		TerminalCommandRequests.claim(claimed)

		assertThat(TerminalCommandRequests.withdraw(claimed)).isFalse()
		assertThat(TerminalCommandRequests.withdraw(enqueue())).isTrue()
	}

	@Test
	fun notStartedIsReported() {
		TerminalCommandRequests.claim(enqueue())!!.notStarted("no session")

		assertThat(refusals).containsExactly("no session")
	}
}
