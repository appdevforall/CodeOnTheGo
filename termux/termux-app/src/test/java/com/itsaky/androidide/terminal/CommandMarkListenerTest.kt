package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import com.termux.terminal.ShellIntegrationMark
import com.termux.terminal.ShellIntegrationMark.Kind
import com.termux.terminal.TerminalSession
import io.mockk.mockk
import org.junit.Test

class CommandMarkListenerTest {
	private val pool = PluginSessionPool(maxPerPlugin = 1)
	private val finished = mutableListOf<Pair<PluginSession, Int>>()
	private val listener = CommandMarkListener(pool) { session, exitCode -> finished += session to exitCode }

	private val session =
		PluginSession(mockk<TerminalSession>(relaxed = true).also { it.mSessionName = "p 1" }).also {
			pool.add("p", it)
			it.begin(TerminalCommand("cmd", null, "p", mockk()))
		}

	private fun mark(
		kind: Kind,
		id: String = "cmd",
		exitCode: Int? = null,
		terminal: TerminalSession = session.terminal,
	) = listener.onShellIntegrationMark(terminal, ShellIntegrationMark(kind, exitCode, mapOf(AgentRunner.ID_OPTION to id)))

	@Test
	fun finishedMarkReportsTheExitCode() {
		mark(Kind.COMMAND_FINISHED, exitCode = 3)

		assertThat(finished).containsExactly(session to 3)
	}

	@Test
	fun finishedMarkWithoutAnExitCodeReportsItUnknown() {
		mark(Kind.COMMAND_FINISHED)

		assertThat(finished).containsExactly(session to CommandMarkListener.UNKNOWN_EXIT_CODE)
	}

	@Test
	fun markOfAnotherCommandIsIgnored() {
		mark(Kind.COMMAND_FINISHED, id = "other", exitCode = 0)

		assertThat(finished).isEmpty()
	}

	@Test
	fun markInASessionNoPluginOpenedIsIgnored() {
		mark(Kind.COMMAND_FINISHED, exitCode = 0, terminal = mockk(relaxed = true))

		assertThat(finished).isEmpty()
	}
}
