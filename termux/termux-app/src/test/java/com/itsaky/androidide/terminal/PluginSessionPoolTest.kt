package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import com.termux.terminal.TerminalSession
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class PluginSessionPoolTest {
	private val pool = PluginSessionPool(maxPerPlugin = 2)
	private val open = mutableSetOf<TerminalSession>()

	private fun session(
		name: String,
		pid: Int = 100,
	) = PluginSession(
		mockk<TerminalSession>(relaxed = true).also {
			it.mSessionName = name
			every { it.pid } returns pid
			open += it
		},
		name,
	)

	private fun busySession(name: String) = session(name).apply { begin(mockk()) }

	private fun slot(owner: String = "p") = pool.slotFor(owner)

	@Test
	fun firstSlotOfAPluginIsNumberOne() {
		assertThat(slot()).isEqualTo(PluginSessionPool.Slot.Free("p 1"))
	}

	@Test
	fun newSessionIsNamedAfterTheLabelNotTheOwner() {
		assertThat(pool.slotFor("com.example.plugin", "Example"))
			.isEqualTo(PluginSessionPool.Slot.Free("Example 1"))
	}

	@Test
	fun idleSessionIsPreferred() {
		val idle = session("p 1").also { pool.add("p", it) }

		assertThat(slot()).isEqualTo(PluginSessionPool.Slot.Idle(idle))
	}

	@Test
	fun busySessionsTakeTheirNamesAndTheLimit() {
		pool.add("p", busySession("p 1"))
		assertThat(slot()).isEqualTo(PluginSessionPool.Slot.Free("p 2"))

		pool.add("p", busySession("p 2"))
		assertThat(slot()).isEqualTo(PluginSessionPool.Slot.Full(listOf("p 1", "p 2")))
	}

	@Test
	fun idleSessionNotAtThePromptIsBusy() {
		pool.add("p", session("p 1"))

		assertThat(pool.slotFor("p") { false }).isEqualTo(PluginSessionPool.Slot.Free("p 2"))
	}

	@Test
	fun pruneReturnsTheClosedSessionsAndFreesTheirNames() {
		val busy = busySession("p 1")
		val idle = session("p 2")
		pool.add("p", busy)
		pool.add("p", idle)
		open -= busy.terminal

		assertThat(pool.prune("p") { it in open }).containsExactly(busy)
		assertThat(pool.find(busy.terminal)).isNull()
		assertThat(pool.find(idle.terminal)).isSameInstanceAs(idle)
		assertThat(pool.slotFor("p") { false }).isEqualTo(PluginSessionPool.Slot.Free("p 1"))
	}

	@Test
	fun sessionKeepsTheNameItWasOpenedAsWhenTheUserRenamesIt() {
		val session = session("p 1").also { pool.add("p", it) }
		session.terminal.mSessionName = "server"

		assertThat(pool.find("p", "p 1")).isSameInstanceAs(session)
		assertThat(pool.slotFor("p") { false }).isEqualTo(PluginSessionPool.Slot.Free("p 2"))
	}

	@Test
	fun anotherPluginsSessionsDoNotCount() {
		pool.add("other", busySession("other 1"))
		pool.add("other", busySession("other 2"))

		assertThat(slot()).isEqualTo(PluginSessionPool.Slot.Free("p 1"))
	}

	@Test
	fun sessionsAreFoundOnlyUnderTheirPlugin() {
		val session = session("p 1").also { pool.add("p", it) }

		assertThat(pool.find("p", "p 1")).isSameInstanceAs(session)
		assertThat(pool.find("other", "p 1")).isNull()
	}

	@Test
	fun removeForgetsTheSession() {
		val session = session("p 1").also { pool.add("p", it) }

		assertThat(pool.remove(session.terminal)).isSameInstanceAs(session)
		assertThat(pool.find(session.terminal)).isNull()
		assertThat(pool.remove(session.terminal)).isNull()
	}

	@Test
	fun shellInTheForegroundIsAtThePrompt() {
		val session = session("p 1", pid = 100)

		assertThat(session.isAtPrompt(100)).isTrue()
		assertThat(session.isAtPrompt(null)).isTrue()
		assertThat(session.isAtPrompt(200)).isFalse()
	}

	@Test
	fun runnerStillExitingIsAtThePrompt() {
		val session = busySession("p 1")

		session.finish(0, runnerPid = 200)

		assertThat(session.isAtPrompt(200)).isTrue()
	}

	@Test
	fun sessionStateIsNullUntilACommandRuns() {
		assertThat(session("p 1").state()).isNull()
	}

	@Test
	fun finishedSessionKeepsTheLastExitUntilTheNextCommand() {
		val session = busySession("p 1")

		val exited = session.finish(3)

		assertThat(exited).isEqualTo(CommandState.Exited("p 1", 3, ""))
		assertThat(session.isIdle).isTrue()
		assertThat(session.state()).isEqualTo(exited)

		session.begin(mockk())
		assertThat(session.state()).isEqualTo(CommandState.Running("p 1", ""))
	}
}
