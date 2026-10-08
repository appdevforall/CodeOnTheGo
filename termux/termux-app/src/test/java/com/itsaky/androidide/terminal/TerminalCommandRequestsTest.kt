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
	private val requests by lazy {
		TerminalCommandRequests(
			runner,
			TerminalCommandRequests.MAX_SESSIONS_PER_PLUGIN,
			io = { it.run() },
			main = { it.run() },
			processes = processes,
			now = { clock },
		)
	}
	private val events = mutableListOf<String>()
	private val opened = mutableListOf<Pair<String, List<String>>>()
	private val shells = mutableMapOf<TerminalSession, Shell>()
	private val closed = mutableSetOf<TerminalSession>()

	// What holds each terminal's foreground; a session missing here cannot be told.
	private val foreground = mutableMapOf<TerminalSession, Int>()

	// The parent of each live process; a process missing here is gone.
	private val parents = mutableMapOf<Int, Int>()
	private var nextPid = 100
	private var clock = 0L

	private val processes =
		object : ProcessProbe {
			override fun foregroundProcessGroup(pid: Int) = foreground.entries.firstOrNull { it.key.pid == pid }?.value

			override fun parentOf(pid: Int) = parents[pid]
		}

	/** A Terminal session as the registry sees it: marks and bytes arrive as the runner prints them. */
	private class Shell(
		name: String,
		val pid: Int,
		exitStatus: Int = 0,
	) {
		private var listener: TerminalSession.ShellIntegrationListener? = null
		private var tap: TerminalEmulator.OutputTap? = null
		private val emulator = mockk<TerminalEmulator>(relaxed = true)
		val terminal =
			mockk<TerminalSession>(relaxed = true).also {
				it.mSessionName = name
				every { it.exitStatus } returns exitStatus
				every { it.pid } returns pid
				every { it.emulator } returns emulator
				every { it.setShellIntegrationListener(any()) } answers { listener = firstArg() }
				every { emulator.setOutputTap(any()) } answers { tap = firstArg() }
			}

		fun mark(
			kind: Kind,
			id: String,
			exitCode: Int? = null,
			runnerPid: Int? = null,
		) = listener!!.onShellIntegrationMark(
			terminal,
			ShellIntegrationMark(
				kind,
				exitCode,
				mapOf(AgentRunner.ID_OPTION to id) + listOfNotNull(runnerPid?.let { AgentRunner.PID_OPTION to "$it" }),
			),
		)

		fun prints(text: String) = text.toByteArray().forEach { tap?.onByte(it) }

		/** The runner's whole run of command [id]. */
		fun runs(
			id: String,
			output: String,
			exitCode: Int,
			runnerPid: Int? = null,
		) {
			mark(Kind.OUTPUT_START, id)
			prints(output)
			mark(Kind.COMMAND_FINISHED, id, exitCode, runnerPid)
		}
	}

	private val factory =
		object : TerminalSessionFactory {
			override fun isOpen(session: TerminalSession) = session !in closed

			override fun foregroundProcessGroup(session: TerminalSession) = foreground[session]

			override fun open(
				name: String,
				bashArguments: Array<String>,
				workingDirectory: String?,
			): TerminalSession {
				opened += name to bashArguments.toList()
				return Shell(name, nextPid++).also { shells[it.terminal] = it }.terminal
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
	fun idleSessionAtThePromptIsReused() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "", exitCode = 0)
		foreground[shell.terminal] = shell.pid

		assertThat(start(enqueue())).isSameInstanceAs(shell)
	}

	@Test
	fun idleSessionStillHeldByTheExitingRunnerIsReused() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "", exitCode = 0, runnerPid = 4242)
		foreground[shell.terminal] = 4242

		assertThat(start(enqueue())).isSameInstanceAs(shell)
	}

	@Test
	fun idleSessionRunningAProgramOfTheUsersIsNotTypedInto() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "", exitCode = 0, runnerPid = 4242)
		// The user started vim at the prompt the command left.
		foreground[shell.terminal] = 5000

		val second = enqueue()
		val other = start(second)

		assertThat(other).isNotSameInstanceAs(shell)
		assertThat(opened.map { it.first }).containsExactly("test.plugin 1", "test.plugin 2").inOrder()
		verify(exactly = 0) { shell.terminal.write(runner.typedRunLine(second)) }
	}

	@Test
	fun busySessionThatLeftTheTerminalReportsItsCommandEnded() {
		val id = enqueue()
		val shell = start(id)!!
		every { shell.terminal.exitStatus } returns 137
		// Gone from the Terminal before it reported finishing.
		closed += shell.terminal

		start(enqueue())

		assertThat(events).contains("exited 137: ")
		assertThat(requests.snapshot(id)).isNull()
		assertThat(opened.map { it.first }).containsExactly("test.plugin 1", "test.plugin 1")
	}

	@Test
	fun busySessionThatLeftTheTerminalStillRunningReportsAnUnknownExit() {
		val id = enqueue()
		val shell = start(id)!!
		every { shell.terminal.isRunning } returns true
		closed += shell.terminal

		start(enqueue())

		assertThat(events).contains("exited ${CommandMarkListener.UNKNOWN_EXIT_CODE}: ")
	}

	@Test
	fun renamedSessionIsStillTheOneItWasOpenedAs() {
		val id = enqueue()
		val shell = start(id)!!
		shell.terminal.mSessionName = "server"

		assertThat(requests.read("test.plugin", id)).isEqualTo(CommandState.Running(id, "test.plugin 1", ""))
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
			.isEqualTo(CommandState.Running(id, "test.plugin 1", "\$ npm start\nlistening on 3000"))
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
	fun killEndsTheSessionOfACommandThatIgnoredCtrlC() {
		val id = enqueue()
		val shell = start(id)!!
		requests.cancel(id)

		requests.kill(id)

		verify { shell.terminal.finishIfRunning() }
		every { shell.terminal.exitStatus } returns 137
		assertThat(requests.onSessionFinished(shell.terminal)).isTrue()
		assertThat(events.last()).isEqualTo("exited 137: ")
	}

	@Test
	fun killLeavesASessionWhoseCommandExitedAlone() {
		val id = enqueue()
		val shell = start(id)!!
		shell.runs(id, "^C\r\n", exitCode = 130)

		requests.kill(id)

		verify(exactly = 0) { shell.terminal.finishIfRunning() }
	}

	@Test
	fun interruptSendsCtrlCAndReturnsTheCommandsListener() {
		val id = enqueue()
		val shell = start(id)!!

		assertThat(requests.interrupt("test.plugin", id)).isSameInstanceAs(listener)
		verify { shell.terminal.write(ControlKeys.CTRL_C) }
	}

	@Test
	fun interruptOfAnExitedOrUnknownOrAnotherPluginsCommandIsNull() {
		val id = enqueue()
		val shell = start(id)!!

		assertThat(requests.interrupt("other.plugin", id)).isNull()
		assertThat(requests.interrupt("test.plugin", "never-queued")).isNull()
		shell.runs(id, "", exitCode = 0)
		assertThat(requests.interrupt("test.plugin", id)).isNull()
		verify(exactly = 0) { shell.terminal.write(ControlKeys.CTRL_C) }
	}

	@Test
	fun stoppingACommandLeavesTheNextOneInItsSessionAlone() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "", exitCode = 0)
		val second = enqueue()
		assertThat(start(second)).isSameInstanceAs(shell)

		assertThat(requests.interrupt("test.plugin", first)).isNull()
		verify(exactly = 0) { shell.terminal.write(ControlKeys.CTRL_C) }
	}

	@Test
	fun readingACommandWhoseSessionWasReusedGivesItsOwnResult() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "dev server stopped\r\n", exitCode = 1)
		val second = enqueue()
		start(second)
		shell.mark(Kind.OUTPUT_START, second)
		shell.prints("running tests\r\n")

		assertThat(requests.read("test.plugin", first)).isEqualTo(CommandState.Exited(first, "test.plugin 1", 1, "dev server stopped"))
		assertThat(requests.read("test.plugin", second)).isEqualTo(CommandState.Running(second, "test.plugin 1", "running tests"))
	}

	@Test
	fun onlyThePluginsLastExitedCommandsAreKept() {
		val ids =
			List(TerminalCommandRequests.EXITED_KEPT_PER_PLUGIN + 1) {
				enqueue().also { id -> start(id)!!.runs(id, "", exitCode = 0) }
			}
		// Another plugin's commands do not push this one's out.
		repeat(TerminalCommandRequests.EXITED_KEPT_PER_PLUGIN) { enqueue("other.plugin").also { id -> start(id)!!.runs(id, "", exitCode = 0) } }

		assertThat(requests.read("test.plugin", ids.first())).isNull()
		assertThat(ids.drop(1).map { requests.read("test.plugin", it) }).doesNotContain(null)
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
		assertThat(requests.read("test.plugin", id)).isEqualTo(CommandState.Exited(id, "test.plugin 1", 137, "\$ ./gradlew --version"))
	}

	@Test
	fun idleSessionExitingIsNotAPluginCommand() {
		val id = enqueue()
		val shell = start(id)!!
		shell.runs(id, "", exitCode = 0)

		assertThat(requests.onSessionFinished(shell.terminal)).isFalse()
	}

	@Test
	fun unrelatedSessionIsNotClaimed() {
		assertThat(requests.onSessionFinished(Shell("user", 1).terminal)).isFalse()
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

		assertThat(requests.read("test.plugin", id))
			.isEqualTo(CommandState.Running(id, "test.plugin 1", "listening on 3000"))
	}

	@Test
	fun readingAnExitedCommandGivesItsExitCodeAndOutput() {
		val id = enqueue()
		start(id)!!.runs(id, "EADDRINUSE\r\n", exitCode = 1)

		assertThat(requests.read("test.plugin", id))
			.isEqualTo(CommandState.Exited(id, "test.plugin 1", 1, "EADDRINUSE"))
	}

	@Test
	fun anotherPluginsCommandIsNotRead() {
		val running = enqueue().also(::start)
		val exited = enqueue().also { start(it)!!.runs(it, "", exitCode = 0) }

		assertThat(requests.read("other.plugin", running)).isNull()
		assertThat(requests.read("other.plugin", exited)).isNull()
		assertThat(requests.read("test.plugin", "never-queued")).isNull()
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

	private fun pastTheGrace() {
		clock += TerminalCommandRequests.GONE_GRACE_MS
	}

	@Test
	fun commandWhoseRunnerDiedWithoutItsEndMarkEndsOnceTheMarkCannotStillBeComing() {
		val id = enqueue()
		val shell = start(id)!!
		shell.mark(Kind.OUTPUT_START, id, runnerPid = 4242)
		shell.prints("partial\r\n")
		// Killed: the runner is gone, and the shell has the terminal back.
		foreground[shell.terminal] = shell.pid

		assertThat(requests.read("test.plugin", id)).isEqualTo(CommandState.Running(id, "test.plugin 1", "partial"))
		pastTheGrace()

		assertThat(requests.read("test.plugin", id))
			.isEqualTo(CommandState.Exited(id, "test.plugin 1", CommandMarkListener.UNKNOWN_EXIT_CODE, "partial"))
		assertThat(events.last()).isEqualTo("exited ${CommandMarkListener.UNKNOWN_EXIT_CODE}: partial")
	}

	@Test
	fun endMarkArrivingWithinTheGraceGivesTheRealExitCode() {
		val id = enqueue()
		val shell = start(id)!!
		shell.mark(Kind.OUTPUT_START, id, runnerPid = 4242)
		foreground[shell.terminal] = shell.pid
		requests.read("test.plugin", id)

		shell.mark(Kind.COMMAND_FINISHED, id, exitCode = 0, runnerPid = 4242)
		pastTheGrace()

		assertThat(requests.read("test.plugin", id)).isEqualTo(CommandState.Exited(id, "test.plugin 1", 0, ""))
		assertThat(events.filter { it.startsWith("exited") }).containsExactly("exited 0: ")
	}

	@Test
	fun commandWhoseRunnerIsAliveKeepsRunning() {
		val id = enqueue()
		val shell = start(id)!!
		shell.mark(Kind.OUTPUT_START, id, runnerPid = 4242)
		parents[4242] = shell.pid
		// A new session's first shell has no job control, so its own group holds the terminal throughout.
		foreground[shell.terminal] = shell.pid

		requests.read("test.plugin", id)
		pastTheGrace()

		assertThat(requests.read("test.plugin", id)).isInstanceOf(CommandState.Running::class.java)
	}

	@Test
	fun commandWhoseProgramHoldsTheTerminalKeepsRunning() {
		val id = enqueue()
		val shell = start(id)!!
		shell.mark(Kind.OUTPUT_START, id, runnerPid = 4242)
		foreground[shell.terminal] = 4242

		requests.read("test.plugin", id)
		pastTheGrace()

		assertThat(requests.read("test.plugin", id)).isInstanceOf(CommandState.Running::class.java)
	}

	@Test
	fun typedRunLineThatNoRunnerTookEndsTheCommandAndNeverRuns() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "", exitCode = 0, runnerPid = 4242)
		foreground[shell.terminal] = shell.pid
		val second = enqueuePrepared()
		assertThat(start(second)).isSameInstanceAs(shell)
		// A `read` at the prompt took the run line: no runner reports in, and the shell keeps the terminal.

		assertThat(requests.read("test.plugin", second)).isInstanceOf(CommandState.Running::class.java)
		pastTheGrace()

		assertThat(requests.read("test.plugin", second))
			.isEqualTo(CommandState.Exited(second, "test.plugin 1", CommandMarkListener.UNKNOWN_EXIT_CODE, ""))
		assertThat(hasFiles(second)).isFalse()
	}

	@Test
	fun typedRunLineTheRunnerTookIsLeftRunning() {
		val first = enqueue()
		val shell = start(first)!!
		shell.runs(first, "", exitCode = 0, runnerPid = 4242)
		foreground[shell.terminal] = shell.pid
		val second = enqueuePrepared()
		start(second)
		// Taken, but its first mark is not processed yet.
		File(tmp.root, "$second.cmd").renameTo(File(tmp.root, "$second.run"))

		pastTheGrace()

		assertThat(requests.read("test.plugin", second)).isInstanceOf(CommandState.Running::class.java)
	}

	@Test
	fun newSessionsCommandIsLeftRunningWhileItsProfileLoads() {
		val id = enqueuePrepared()
		val shell = start(id)!!
		foreground[shell.terminal] = shell.pid

		pastTheGrace()

		assertThat(requests.read("test.plugin", id)).isInstanceOf(CommandState.Running::class.java)
		assertThat(hasFiles(id)).isTrue()
	}

	@Test
	fun sessionOfACommandWhoseRunnerDiedIsReused() {
		val shells =
			List(TerminalCommandRequests.MAX_SESSIONS_PER_PLUGIN) { n ->
				val id = enqueue()
				start(id)!!.also {
					it.mark(Kind.OUTPUT_START, id, runnerPid = 4000 + n)
					foreground[it.terminal] = it.pid
					// The first one's runner was killed.
					if (n > 0) parents[4000 + n] = it.pid
				}
			}

		assertThat(start(enqueue())).isNull()
		pastTheGrace()

		assertThat(start(enqueue())).isSameInstanceAs(shells.first())
	}
}
