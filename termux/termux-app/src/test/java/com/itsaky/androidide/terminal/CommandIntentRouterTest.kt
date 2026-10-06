package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import com.termux.terminal.TerminalSession
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommandIntentRouterTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val requests by lazy { TerminalCommandRequests(AgentRunner(tmp.root), 1) { it.run() } }
	private val router by lazy { CommandIntentRouter(requests) }
	private val events = mutableListOf<String>()

	private val factory =
		object : TerminalSessionFactory {
			override fun isOpen(session: TerminalSession) = true

			override fun open(
				name: String,
				bashArguments: Array<String>,
				workingDirectory: String?,
			): TerminalSession = mockk<TerminalSession>(relaxed = true).also { it.mSessionName = name }
		}

	private val listener =
		object : TerminalCommandListener {
			override fun onStarted(sessionName: String) {
				events += "started $sessionName"
			}

			override fun onExited(
				exitCode: Int,
				output: String,
			) = Unit

			override fun onNotStarted(reason: TerminalStartFailure) {
				events += "not started $reason"
			}
		}

	private fun enqueue(id: String = "cmd") = id.also { requests.enqueue(it, null, "p", listener) }

	@Test
	fun queuedCommandStartsInASession() {
		val session = router.route(enqueue(), factory, screenClosing = false)

		assertThat(session?.mSessionName).isEqualTo("p 1")
		assertThat(events).containsExactly("started p 1")
	}

	@Test
	fun commandIsRoutedOnlyOnce() {
		val id = enqueue()
		router.route(id, factory, screenClosing = false)

		assertThat(router.route(id, factory, screenClosing = false)).isNull()
		assertThat(events).hasSize(1)
	}

	@Test
	fun closingScreenWithdrawsTheCommand() {
		val id = enqueue()

		assertThat(router.route(id, factory, screenClosing = true)).isNull()
		assertThat(events).containsExactly("not started ${TerminalStartFailure.TerminalClosed}")
		assertThat(requests.claim(id)).isNull()
	}

	@Test
	fun unknownCommandIsNotRouted() {
		assertThat(router.route("never-queued", factory, screenClosing = false)).isNull()
		assertThat(events).isEmpty()
	}
}
