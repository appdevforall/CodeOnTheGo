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
	private val runnerPids = mutableListOf<Int?>()
	private val listener =
		CommandMarkListener(pool) { session, exitCode, runnerPid ->
			finished += session to exitCode
			runnerPids += runnerPid
		}

	private val session =
		PluginSession(mockk(relaxed = true), "p 1").also {
			pool.add("p", it)
			it.begin(TerminalCommand("cmd", null, "p", mockk()))
		}

	private fun mark(
		kind: Kind,
		id: String = "cmd",
		exitCode: Int? = null,
		terminal: TerminalSession = session.terminal,
		options: Map<String, String> = emptyMap(),
	) = listener.onShellIntegrationMark(terminal, ShellIntegrationMark(kind, exitCode, mapOf(AgentRunner.ID_OPTION to id) + options))

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
	fun finishedMarkReportsTheRunnersPid() {
		mark(Kind.COMMAND_FINISHED, exitCode = 0, options = mapOf(AgentRunner.PID_OPTION to "4242"))
		mark(Kind.COMMAND_FINISHED, exitCode = 0, options = mapOf(AgentRunner.PID_OPTION to "not a pid"))

		assertThat(runnerPids).containsExactly(4242, null).inOrder()
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
