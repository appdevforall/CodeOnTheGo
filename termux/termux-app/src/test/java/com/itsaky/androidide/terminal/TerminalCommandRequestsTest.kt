package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import com.termux.terminal.ShellIntegrationMark
import com.termux.terminal.ShellIntegrationMark.Kind
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TerminalCommandRequestsTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val runner by lazy { AgentRunner(tmp.root, "\$TMPDIR") }
	private val requests by lazy { TerminalCommandRequests(runner, TerminalCommandRequests.MAX_SESSIONS_PER_PLUGIN) { it.run() } }
	private val events = mutableListOf<String>()
	private val opened = mutableListOf<Pair<String, List<String>>>()
	private val shells = mutableMapOf<TerminalSession, Shell>()
	private val closed = mutableSetOf<TerminalSession>()

	/** A Terminal session as the registry sees it: marks and bytes arrive as the runner prints them. */
	private class Shell(
		name: String,
		exitStatus: Int = 0,
	) {
		private var listener: TerminalSession.ShellIntegrationListener? = null
		private var tap: TerminalEmulator.OutputTap? = null
		private val emulator = mockk<TerminalEmulator>(relaxed = true)
		val terminal =
			mockk<TerminalSession>(relaxed = true).also {
				it.mSessionName = name
				every { it.exitStatus } returns exitStatus
				every { it.emulator } returns emulator
				every { it.setShellIntegrationListener(any()) } answers { listener = firstArg() }
				every { emulator.setOutputTap(any()) } answers { tap = firstArg() }
			}

		fun mark(
			kind: Kind,
			id: String,
			exitCode: Int? = null,
		) = listener!!.onShellIntegrationMark(terminal, ShellIntegrationMark(kind, exitCode, mapOf(AgentRunner.ID_OPTION to id)))

		fun prints(text: String) = text.toByteArray().forEach { tap?.onByte(it) }

		/** The runner's whole run of command [id]. */
		fun runs(
			id: String,
			output: String,
			exitCode: Int,
		) {
			mark(Kind.OUTPUT_START, id)
			prints(output)
			mark(Kind.COMMAND_FINISHED, id, exitCode)
		}
	}

	private val factory =
		object : TerminalSessionFactory {
			override fun isOpen(session: TerminalSession) = session !in closed

			override fun open(
				name: String,
				bashArguments: Array<String>,
				workingDirectory: String?,
			): TerminalSession {
				opened += name to bashArguments.toList()
				return Shell(name).also { shells[it.terminal] = it }.terminal
			}
		}

	private val listener =
		object : TerminalCommandListener {
			override fun onStarted(sessionName: String) {
				events += "started $sessionName"
			}

			override fun onExited(
				exitCode: Int,
				output: String,
			) {
				events += "exited $exitCode: $output"
			}

			override fun onNotStarted(reason: TerminalStartFailure) {
				events += "not started $reason"
			}
		}

	private var nextId = 0

	private fun enqueue(owner: String = "test.plugin"): String =
		"cmd${nextId++}".also { requests.enqueue(it, "/project", owner, listener) }

	/** Queues a command the runner prepared, so its files exist. */
	private fun enqueuePrepared(owner: String = "test.plugin"): String =
		runner.prepare("true", null).also { requests.enqueue(it, "/project", owner, listener) }

	private fun hasFiles(id: String) = File(tmp.root, "$id.cmd").exists() || File(tmp.root, "$id.dir").exists()

	private fun start(id: String): Shell? = requests.start(id, factory)?.let(shells::getValue)

	@Test
	fun startIsOneShot() {
		val id = enqueue()

		assertThat(start(id)).isNotNull()
		assertThat(start(id)).isNull()
		assertThat(events).containsExactly("started test.plugin 1")
	}

	@Test
	fun firstCommandOpensASessionThatRunsIt() {
		val id = enqueue()

		start(id)

		assertThat(opened).containsExactly("test.plugin 1" to runner.firstRunArguments(id).toList())
		assertThat(events).containsExactly("started test.plugin 1")
	}

	@Test
	fun exitReportsTheCodeAndOnlyWhatTheCommandPrinted() {
		val id = enqueue()
		val shell = start(id)!!
		shell.prints("~ \$ prompt text before the command\r\n")

		shell.runs(id, "\$ ./check.sh\r\nboom\r\n", exitCode = 2)

		assertThat(events.last()).isEqualTo("exited 2: \$ ./check.sh\nboom")
	}

	@Test
	fun idleSessionIsReusedForTheNextCommand() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "ok\r\n", exitCode = 0)

		val second = enqueue()
		val reused = start(second)

		assertThat(reused).isSameInstanceAs(shell)
		assertThat(opened).hasSize(1)
		verify { shell.terminal.write(runner.typedRunLine(second)) }
	}

	@Test
	fun busySessionMeansANewOne() {
		start(enqueue())

		start(enqueue())

		assertThat(opened.map { it.first }).containsExactly("test.plugin 1", "test.plugin 2").inOrder()
	}

	@Test
	fun commandIsRefusedWhileEverySessionIsBusy() {
		repeat(TerminalCommandRequests.MAX_SESSIONS_PER_PLUGIN) { start(enqueue()) }

		assertThat(start(enqueue())).isNull()

		assertThat(opened).hasSize(TerminalCommandRequests.MAX_SESSIONS_PER_PLUGIN)
		assertThat(events.last()).isEqualTo("not started ${TerminalStartFailure.AllSessionsBusy(listOf("test.plugin 1", "test.plugin 2", "test.plugin 3"))}")
	}

	@Test
	fun closedSessionIsNotReused() {
		val id = enqueue()
		val shell = start(id)!!
		shell.runs(id, "", exitCode = 0)
		closed += shell.terminal

		start(enqueue())

		assertThat(opened.map { it.first }).containsExactly("test.plugin 1", "test.plugin 1")
	}

	@Test
	fun marksOfAnotherCommandAreIgnored() {
		val id = enqueue()
		val shell = start(id)!!

		shell.mark(Kind.COMMAND_FINISHED, "someone-else", exitCode = 0)

		assertThat(events).containsExactly("started test.plugin 1")
	}

	@Test
	fun finishedMarkWithoutAnExitCodeReportsItUnknown() {
		val id = enqueue()
		val shell = start(id)!!

		shell.mark(Kind.COMMAND_FINISHED, id)

		assertThat(events.last()).isEqualTo("exited ${CommandMarkListener.UNKNOWN_EXIT_CODE}: ")
	}

	@Test
	fun snapshotIsTheSessionAndTheOutputSoFar() {
		val id = enqueue()
		val shell = start(id)!!
		shell.mark(Kind.OUTPUT_START, id)
		shell.prints("\$ npm start\r\nlistening on 3000\r\n")

		assertThat(requests.snapshot(id))
			.isEqualTo(CommandState.Running("test.plugin 1", "\$ npm start\nlistening on 3000"))
	}

	@Test
	fun snapshotIsNullOnceTheCommandExited() {
		val id = enqueue()
		start(id)!!.runs(id, "", exitCode = 0)

		assertThat(requests.snapshot(id)).isNull()
	}

	@Test
	fun cancelBeforeStartMeansItNeverRuns() {
		val id = enqueue()

		requests.cancel(id)

		assertThat(start(id)).isNull()
		assertThat(opened).isEmpty()
		assertThat(events).isEmpty()
	}

	@Test
	fun cancelWhileRunningSendsCtrlC() {
		val id = enqueue()
		val shell = start(id)!!

		requests.cancel(id)

		verify { shell.terminal.write(ControlKeys.CTRL_C) }
	}

	@Test
	fun interruptedCommandReportsItsExitCodeAndLeavesTheSessionForTheNext() {
		val id = enqueue()
		val shell = start(id)!!
		requests.cancel(id)

		// What the runner reports once Ctrl-C stops the command.
		shell.runs(id, "^C\r\n", exitCode = 130)

		assertThat(events.last()).isEqualTo("exited 130: ^C")
		assertThat(start(enqueue())).isSameInstanceAs(shell)
		assertThat(opened).hasSize(1)
	}

	@Test
	fun allSessionsBusyNamesTheBusySessions() {
		val failure = TerminalStartFailure.AllSessionsBusy(listOf("p 1", "p 2"))

		assertThat(failure.message).contains("(p 1, p 2)")
	}

	@Test
	fun sessionDyingMidCommandReportsItAndStaysOpen() {
		val id = enqueue()
		val shell = start(id)!!
		shell.mark(Kind.OUTPUT_START, id)
		shell.prints("\$ ./gradlew --version\r\n\r\n[Process completed (signal 9) - press Enter]")
		every { shell.terminal.exitStatus } returns 137

		assertThat(requests.onSessionFinished(shell.terminal)).isTrue()
		assertThat(events.last()).isEqualTo("exited 137: \$ ./gradlew --version")
		assertThat(requests.read("test.plugin", "test.plugin 1")).isNull()
	}

	@Test
	fun idleSessionExitingIsNotAPluginCommand() {
		val id = enqueue()
		val shell = start(id)!!
		shell.runs(id, "", exitCode = 0)

		assertThat(requests.onSessionFinished(shell.terminal)).isFalse()
		assertThat(requests.read("test.plugin", "test.plugin 1")).isNull()
	}

	@Test
	fun unrelatedSessionIsNotClaimed() {
		assertThat(requests.onSessionFinished(Shell("user").terminal)).isFalse()
	}

	@Test
	fun withdrawOnlyTakesBackAnUnstartedCommandAndReportsWhy() {
		val started = enqueue().also(::start)

		assertThat(requests.withdraw(started, TerminalStartFailure.TerminalDidNotOpen)).isFalse()
		assertThat(requests.withdraw(enqueue(), TerminalStartFailure.TerminalDidNotOpen)).isTrue()
		assertThat(events).containsExactly("started test.plugin 1", "not started ${TerminalStartFailure.TerminalDidNotOpen}").inOrder()
	}

	@Test
	fun readingARunningCommandGivesItsOutputSoFar() {
		val id = enqueue()
		val shell = start(id)!!
		shell.mark(Kind.OUTPUT_START, id)
		shell.prints("listening on 3000\r\n")

		assertThat(requests.read("test.plugin", "test.plugin 1"))
			.isEqualTo(CommandState.Running("test.plugin 1", "listening on 3000"))
	}

	@Test
	fun readingAnExitedCommandGivesItsExitCodeAndOutput() {
		val id = enqueue()
		start(id)!!.runs(id, "EADDRINUSE\r\n", exitCode = 1)

		assertThat(requests.read("test.plugin", "test.plugin 1"))
			.isEqualTo(CommandState.Exited("test.plugin 1", 1, "EADDRINUSE"))
	}

	@Test
	fun anotherPluginsSessionIsNotRead() {
		start(enqueue())

		assertThat(requests.read("other.plugin", "test.plugin 1")).isNull()
		assertThat(requests.read("test.plugin", "test.plugin 2")).isNull()
	}

	@Test
	fun closedSessionIsNotRead() {
		val shell = start(enqueue())!!
		requests.onSessionFinished(shell.terminal)

		assertThat(requests.read("test.plugin", "test.plugin 1")).isNull()
	}

	@Test
	fun commandsThatNeverRunLeaveNoFiles() {
		val withdrawn = enqueuePrepared().also { requests.withdraw(it, TerminalStartFailure.NotInForeground) }
		val cancelled = enqueuePrepared().also(requests::cancel)

		assertThat(listOf(withdrawn, cancelled).filter(::hasFiles)).isEmpty()
	}

	@Test
	fun refusedCommandLeavesNoFiles() {
		repeat(TerminalCommandRequests.MAX_SESSIONS_PER_PLUGIN) { start(enqueue()) }
		val refused = enqueuePrepared()

		assertThat(start(refused)).isNull()
		assertThat(hasFiles(refused)).isFalse()
	}

	@Test
	fun startedCommandKeepsItsFilesForTheRunner() {
		val id = enqueuePrepared()

		start(id)!!.runs(id, "", exitCode = 0)

		assertThat(hasFiles(id)).isTrue()
	}
}
