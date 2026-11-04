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

	private val requests by lazy {
		TerminalCommandRequests(AgentRunner(tmp.root), 1, io = { it.run() }, main = { it.run() }, processes = UnreadableProcesses, now = { 0L })
	}

	private object UnreadableProcesses : ProcessProbe {
		override fun foregroundProcessGroup(pid: Int): Int? = null

		override fun parentOf(pid: Int): Int? = null
	}
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

	private fun route(
		id: String,
		screenClosing: Boolean = false,
	): TerminalSession? {
		var session: TerminalSession? = null
		router.route(id, factory, { screenClosing }) { session = it }
		return session
	}

	@Test
	fun queuedCommandStartsInASession() {
		val session = route(enqueue())

		assertThat(session?.mSessionName).isEqualTo("p 1")
		assertThat(events).containsExactly("started p 1")
	}

	@Test
	fun commandIsRoutedOnlyOnce() {
		val id = enqueue()
		route(id)

		assertThat(route(id)).isNull()
		assertThat(events).hasSize(1)
	}

	@Test
	fun closingScreenWithdrawsTheCommand() {
		val id = enqueue()

		assertThat(route(id, screenClosing = true)).isNull()
		assertThat(events).containsExactly("not started ${TerminalStartFailure.TerminalClosed}")
		assertThat(route(id)).isNull()
	}

	@Test
	fun unknownCommandIsNotRouted() {
		assertThat(route("never-queued")).isNull()
		assertThat(events).isEmpty()
	}
}
