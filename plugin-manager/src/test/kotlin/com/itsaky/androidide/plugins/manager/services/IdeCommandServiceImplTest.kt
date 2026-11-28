package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.CommandExecution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class IdeCommandServiceImplTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val projectRoot by lazy { tmp.newFolder("project") }
	private val filesDir by lazy { tmp.newFolder("files") }
	private val pidFile by lazy { File(tmp.root, "pids") }

	/** Created by [releaseOnceReading] to end a script's [WAIT_FOR_RELEASE]. */
	private val releaseFile by lazy { File(tmp.root, "release") }

	/** `setsid` and `kill` from util-linux and procps, standing in for Android's toybox and [android.system.Os]. */
	private val hostGroups = ProcessGroups(SETSID, ::hostSignal)

	private fun hostSignal(
		pgid: Int,
		signal: GroupSignal,
	): Boolean {
		val name =
			when (signal) {
				GroupSignal.PROBE -> "0"
				GroupSignal.TERM -> "TERM"
				GroupSignal.KILL -> "KILL"
			}
		return ProcessBuilder(KILL, "-s", name, "--", "-$pgid")
			.redirectOutput(DEV_NULL)
			.redirectError(DEV_NULL)
			.start()
			.waitFor() == 0
	}

	@After
	fun killLeftovers() {
		if (pidFile.exists()) {
			for (pid in pidFile.readLines().mapNotNull { it.trim().toIntOrNull() }) {
				ProcessBuilder(KILL, "-s", "KILL", "$pid").start().waitFor()
			}
		}
		// A cancel returns before its readers end, so they must not count against the next test.
		val deadline = System.currentTimeMillis() + 2_000
		while (readerThreads().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
	}

	private fun service(processGroups: ProcessGroups = hostGroups) = serviceFinding { processGroups }

	private fun serviceFinding(processGroups: () -> ProcessGroups) =
		IdeCommandServiceImpl(
			pluginId = "test.plugin",
			permissions = setOf(PluginPermission.SYSTEM_COMMANDS),
			projectRootProvider = { projectRoot },
			appFilesDir = filesDir,
			processGroups = processGroups,
		)

	private fun resultFor(workingDirectory: String): CommandResult =
		resultFor(CommandSpec.ShellCommand("true", workingDirectory = workingDirectory))

	private fun resultFor(spec: CommandSpec): CommandResult {
		val execution = service(ProcessGroups.android(File(filesDir, "usr/bin/setsid"))).executeCommand(spec, 5_000)
		return runBlocking { execution.await() }
	}

	/**
	 * `sh -c [script]`, where `$1` is [pidFile] for the script to write the ids of its processes to,
	 * and `$2` is [releaseFile].
	 */
	private fun shell(script: String) = CommandSpec.ShellCommand("sh", listOf("-c", script, "sh", pidFile.path, releaseFile.path))

	/**
	 * Lets a script that echoed `done` and ends in [WAIT_FOR_RELEASE] exit, once `done` has been read
	 * and both output readers are blocked in read() again, where they hold the output open.
	 */
	private fun CommandExecution.releaseOnceReading() {
		runBlocking { withTimeout(5_000) { output.first { it == CommandOutput.StdOut("done") } } }
		val deadline = System.currentTimeMillis() + 5_000
		while (Thread.getAllStackTraces().values.count { it.inReader() && it.inRead() } < 2) {
			check(System.currentTimeMillis() < deadline) { "the output readers are not in read()" }
			Thread.sleep(5)
		}
		releaseFile.createNewFile()
	}

	private fun CommandExecution.awaitWithin(ms: Long): CommandResult = runBlocking { withTimeout(ms) { await() } }

	/** [service]'s running-command count once it is [expected], or as it stands after [ms]. */
	private fun countWithin(
		service: IdeCommandServiceImpl,
		expected: Int,
		ms: Long,
	): Int {
		val deadline = System.currentTimeMillis() + ms
		while (service.getRunningCommandCount() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
		return service.getRunningCommandCount()
	}

	/** The [count] process ids the command wrote to [pidFile], once it has written them. */
	private fun pids(count: Int): List<Int> {
		val deadline = System.currentTimeMillis() + 5_000
		while (System.currentTimeMillis() < deadline) {
			val pids = if (pidFile.exists()) pidFile.readLines().mapNotNull { it.trim().toIntOrNull() } else emptyList()
			if (pids.size >= count) return pids
			Thread.sleep(10)
		}
		throw AssertionError("the command wrote no $count process ids to $pidFile")
	}

	private fun alive(pid: Int): Boolean {
		val stat = File("/proc/$pid/stat").takeIf { it.exists() }?.runCatching { readText() }?.getOrNull() ?: return false
		// The state follows the parenthesised name; a zombie has exited.
		return stat.substringAfterLast(") ").first() !in "ZX"
	}

	/** Whether every process in [pids] is gone within [ms]. */
	private fun goneWithin(
		pids: List<Int>,
		ms: Long,
	): Boolean {
		val deadline = System.currentTimeMillis() + ms
		while (pids.any(::alive)) {
			if (System.currentTimeMillis() > deadline) return false
			Thread.sleep(10)
		}
		return true
	}

	/** Whether every command's output reader has ended within [ms]. A cancel returns before they do. */
	private fun readersGoneWithin(ms: Long): Boolean {
		val deadline = System.currentTimeMillis() + ms
		while (readerThreads().isNotEmpty()) {
			if (System.currentTimeMillis() > deadline) return false
			Thread.sleep(10)
		}
		return true
	}

	/** Threads still in a command's output reader. */
	private fun readerThreads(): List<String> =
		Thread
			.getAllStackTraces()
			.filterValues { it.inReader() }
			.keys
			.map { it.name }

	private fun Array<StackTraceElement>.inReader(): Boolean {
		val reader = "${CommandExecutionImpl::class.java.name}\$readLines"
		return any { it.className.startsWith(reader) }
	}

	/** Blocked reading a pipe, inside the process stream's lock that its exit has to take to close it. */
	private fun Array<StackTraceElement>.inRead(): Boolean = any { it.className == FileInputStream::class.java.name }

	private fun assumeHostTools() =
		assumeTrue(
			"needs util-linux setsid and procps kill",
			File(SETSID).canExecute() && File(KILL).canExecute(),
		)

	@Test
	fun `a working directory with a NUL character fails the command`() {
		val result = resultFor("sub\u0000dir")

		assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
		assertThat((result as CommandResult.Failure).error).startsWith("Invalid working directory")
	}

	@Test
	fun `a working directory too long to resolve fails the command`() {
		val result = resultFor("a/".repeat(3000))

		assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
		assertThat((result as CommandResult.Failure).error).startsWith("Invalid working directory")
	}

	@Test
	fun `an environment variable with a NUL character fails the command`() {
		val result = resultFor(CommandSpec.ShellCommand("true", environment = mapOf("KEY" to "a\u0000b")))

		assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
		assertThat((result as CommandResult.Failure).error).startsWith("Invalid environment")
	}

	@Test
	fun `a command past its timeout is stopped with every process it started`() {
		assumeHostTools()
		val started = System.currentTimeMillis()

		val result = service().executeCommand(shell(CHILDREN_SCRIPT), 1_000).awaitWithin(10_000)

		assertThat(System.currentTimeMillis() - started).isLessThan(2_000)
		assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
		assertThat((result as CommandResult.Failure).error).isEqualTo("Command timed out after 1000ms")
		assertThat(pids(CHILDREN_SCRIPT_PIDS).filter(::alive)).isEmpty()
		assertThat(readerThreads()).isEmpty()
	}

	@Test
	fun `cancel stops the command and every process it started`() {
		assumeHostTools()
		val execution = service().executeCommand(shell(CHILDREN_SCRIPT), 60_000)
		val pids = pids(CHILDREN_SCRIPT_PIDS)

		execution.cancel()

		assertThat(execution.awaitWithin(1_000)).isInstanceOf(CommandResult.Cancelled::class.java)
		assertThat(readersGoneWithin(1_000)).isTrue()
		assertThat(goneWithin(pids, 1_000)).isTrue()
	}

	@Test
	fun `cancel while the process is starting stops it once it has started`() {
		assumeHostTools()
		val starting = CountDownLatch(1)
		val release = CountDownLatch(1)
		val script = "sleep 600 & echo \$! >> \"\$1\"; echo \$\$ >> \"\$1\"; wait"
		val execution =
			CommandExecutionImpl(
				"test",
				{
					starting.countDown()
					release.await()
					// Returns once the command has started a child, so that a stop must reach both.
					val process = ProcessBuilder(SETSID, "sh", "-c", script, "sh", pidFile.path).start().also { pids(2) }
					StartedCommand(process, grouped = true, hostGroups)
				},
				timeoutMs = 60_000,
			)
		execution.start {}
		starting.await()

		execution.cancel()
		release.countDown()

		assertThat(goneWithin(pids(2), 1_000)).isTrue()
	}

	@Test
	fun `cancelAllCommands stops every process the commands started`() {
		assumeHostTools()
		val service = service()
		service.executeCommand(shell(CHILDREN_SCRIPT), 60_000)
		val pids = pids(CHILDREN_SCRIPT_PIDS)

		service.cancelAllCommands()

		assertThat(readersGoneWithin(1_000)).isTrue()
		assertThat(goneWithin(pids, 1_000)).isTrue()
		assertThat(countWithin(service, 0, 1_000)).isEqualTo(0)
	}

	@Test
	fun `a command that exits before its timeout is not timed out while a child holds its output`() {
		assumeHostTools()
		val script = "echo done; sleep 600 & echo \$! >> \"\$1\"; $WAIT_FOR_RELEASE"
		// The output stays open past the 2 s limit, until the grace ends and the child is stopped.
		val execution =
			CommandExecutionImpl(
				"test",
				{
					val process = ProcessBuilder(SETSID, "sh", "-c", script, "sh", pidFile.path, releaseFile.path).start()
					StartedCommand(process, grouped = true, hostGroups)
				},
				timeoutMs = 2_000,
				outputGraceMs = 3_000,
			)
		execution.start {}
		execution.releaseOnceReading()

		val result = execution.awaitWithin(10_000)

		assertThat(result).isInstanceOf(CommandResult.Success::class.java)
		result as CommandResult.Success
		assertThat(result.stdout).isEqualTo("done\n")
		assertThat(result.stderr).isEmpty()
		assertThat(result.durationMs).isAtLeast(3_000)
		assertThat(pids(1).filter(::alive)).isEmpty()
	}

	@Test
	fun `a process that ignores SIGTERM is killed`() {
		assumeHostTools()
		// The ignored TERM is inherited by the shell's child.
		val script = "trap '' TERM; echo \$\$ >> \"\$1\"; sleep 600 & echo \$! >> \"\$1\"; wait"

		val result = service().executeCommand(shell(script), 300).awaitWithin(10_000)

		assertThat((result as CommandResult.Failure).error).isEqualTo("Command timed out after 300ms")
		assertThat(goneWithin(pids(2), 1_000)).isTrue()
	}

	@Test
	fun `a command returns soon after it exits though a child holds its output`() {
		assumeHostTools()
		val execution = service().executeCommand(shell("echo done; sleep 600 & echo \$! >> \"\$1\"; $WAIT_FOR_RELEASE"), 60_000)
		execution.releaseOnceReading()
		val released = System.currentTimeMillis()

		val result = execution.awaitWithin(10_000)

		// What the changelog promises: within about 1.5 s of the exit.
		assertThat(System.currentTimeMillis() - released).isLessThan(1_500)
		assertThat(result).isInstanceOf(CommandResult.Success::class.java)
		assertThat((result as CommandResult.Success).stdout).isEqualTo("done\n")
		assertThat(readerThreads()).isEmpty()
		assertThat(pids(1).filter(::alive)).isEmpty()
	}

	@Test
	fun `a command that exits with its output closed may leave a process running`() {
		assumeHostTools()

		val result = service().executeCommand(shell("sleep 600 >/dev/null 2>&1 & echo \$! >> \"\$1\""), 60_000).awaitWithin(10_000)

		assertThat(result).isInstanceOf(CommandResult.Success::class.java)
		assertThat(pids(1).filter(::alive)).hasSize(1)
	}

	@Test
	fun `stopped commands free their slots`() {
		assumeHostTools()
		val service = service()
		// Long enough that none has timed out by the fourth call, even on a slow machine.
		val timedOut = List(3) { service.executeCommand(shell("sleep 600"), 1_000) }
		assertThrows(IllegalStateException::class.java) { service.executeCommand(shell("true"), 1_000) }

		timedOut.forEach { it.awaitWithin(10_000) }
		val deadline = System.currentTimeMillis() + 1_000
		while (service.getRunningCommandCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
		val cancelled = List(3) { service.executeCommand(shell("sleep 600"), 60_000) }
		cancelled.forEach { service.cancelCommand(it.executionId) }
		cancelled.forEach { it.awaitWithin(5_000) }

		assertThat(service.getRunningCommandCount()).isEqualTo(0)
		assertThat(service.executeCommand(shell("true"), 5_000).awaitWithin(5_000)).isInstanceOf(CommandResult.Success::class.java)
	}

	@Test
	fun `a command's slot is free once its await returns`() {
		assumeHostTools()
		val service = service()
		val running = mutableListOf<Int>()

		runBlocking {
			// Unconfined: the code after await() runs inside the completion, as soon as the result is set.
			val finished = service.executeCommand(shell("sleep 0.2"), 5_000)
			launch(Dispatchers.Unconfined) {
				finished.await()
				running += service.getRunningCommandCount()
			}.join()
			val cancelled = service.executeCommand(shell("sleep 600"), 60_000)
			val waiter =
				launch(Dispatchers.Unconfined) {
					cancelled.await()
					running += service.getRunningCommandCount()
				}
			cancelled.cancel()
			waiter.join()
		}

		assertThat(running).containsExactly(0, 0)
	}

	@Test
	fun `a cancelled command keeps its slot until it has stopped`() {
		assumeHostTools()
		val stopping = CountDownLatch(1)
		val release = CountDownLatch(1)
		val service = service(ProcessGroups(SETSID, holdingTerm(stopping, release)))
		val commands = List(3) { service.executeCommand(shell(STARTED_SLEEP), 60_000) }
		// Started, so that the cancel has a group to stop.
		pids(3)

		commands[0].cancel()
		try {
			assertThat(stopping.await(5, TimeUnit.SECONDS)).isTrue()
			assertThrows(IllegalStateException::class.java) { service.executeCommand(shell("true"), 5_000) }
		} finally {
			release.countDown()
		}

		assertThat(commands[0].awaitWithin(5_000)).isInstanceOf(CommandResult.Cancelled::class.java)
		assertThat(service.executeCommand(shell("true"), 5_000).awaitWithin(5_000)).isInstanceOf(CommandResult.Success::class.java)
		commands.drop(1).forEach {
			it.cancel()
			it.awaitWithin(5_000)
		}
	}

	@Test
	fun `cancelAllCommands frees each slot only once its command has stopped`() {
		assumeHostTools()
		val stopping = CountDownLatch(1)
		val release = CountDownLatch(1)
		val service = service(ProcessGroups(SETSID, holdingTerm(stopping, release)))
		val command = service.executeCommand(shell(STARTED_SLEEP), 60_000)
		pids(1)

		service.cancelAllCommands()
		try {
			assertThat(stopping.await(5, TimeUnit.SECONDS)).isTrue()
			assertThat(service.getRunningCommandCount()).isEqualTo(1)
		} finally {
			release.countDown()
		}

		command.awaitWithin(5_000)
		assertThat(service.getRunningCommandCount()).isEqualTo(0)
	}

	@Test
	fun `a command cancelled before its coroutine runs still frees its slot and completes`() {
		val freed = AtomicBoolean(false)
		val execution = CommandExecutionImpl("test", { throw AssertionError("started a cancelled command") }, timeoutMs = 5_000)

		// What a cancel landing between start() and the coroutine's first run leads to: no body runs.
		execution.cancel()
		execution.start { freed.set(true) }

		assertThat(execution.awaitWithin(1_000)).isInstanceOf(CommandResult.Cancelled::class.java)
		assertThat(freed.get()).isTrue()
	}

	/** Signals as [hostSignal] does, but holds the first TERM until [release]. */
	private fun holdingTerm(
		stopping: CountDownLatch,
		release: CountDownLatch,
	): (Int, GroupSignal) -> Boolean =
		{ pgid, signal ->
			if (signal == GroupSignal.TERM) {
				stopping.countDown()
				release.await()
			}
			hostSignal(pgid, signal)
		}

	@Test
	fun `an Error starting a command fails it and frees its slot without escaping`() {
		val service = serviceFinding { throw LinkageError("no setsid for this test") }

		val (result, uncaught) = catchingUncaught { service.executeCommand(shell("true"), 5_000).awaitWithin(5_000) }

		assertThat((result as CommandResult.Failure).error).isEqualTo("Unexpected error: no setsid for this test")
		assertThat(service.getRunningCommandCount()).isEqualTo(0)
		assertThat(uncaught).isEmpty()
	}

	@Test
	fun `a command whose stop throws still completes and frees its slot`() {
		assumeHostTools()
		val service = service(ProcessGroups(SETSID) { _, _ -> throw SecurityException("no signals for this test") })

		val (result, _) =
			catchingUncaught { service.executeCommand(shell(STARTED_SLEEP), 300).awaitWithin(5_000) }

		assertThat((result as CommandResult.Failure).error).isEqualTo("Unexpected error: no signals for this test")
		assertThat(service.getRunningCommandCount()).isEqualTo(0)
	}

	/** [block]'s result, and what reached the default uncaught-exception handler meanwhile. */
	private fun <T> catchingUncaught(block: () -> T): Pair<T, List<Throwable>> {
		val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
		try {
			return block() to uncaught.toList()
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previous)
		}
	}

	@Test
	fun `setsid is looked for as a shell command starts, not as the plugin loads or on the caller's thread`() {
		assumeHostTools()
		val looks = AtomicInteger()
		val looking = CountDownLatch(1)
		val release = CountDownLatch(1)
		val service =
			serviceFinding {
				looks.incrementAndGet()
				looking.countDown()
				release.await()
				hostGroups
			}
		assertThat(looks.get()).isEqualTo(0)

		val execution = AtomicReference<CommandExecution>()
		val caller = thread { execution.set(service.executeCommand(shell("true"), 5_000)) }
		try {
			caller.join(2_000)
			assertThat(execution.get()).isNotNull()
			assertThat(looking.await(5, TimeUnit.SECONDS)).isTrue()
		} finally {
			release.countDown()
		}

		assertThat(execution.get().awaitWithin(5_000)).isInstanceOf(CommandResult.Success::class.java)
		assertThat(looks.get()).isEqualTo(1)
	}

	// Hal's review of a63b453e6: on a slow device the check took up to 10 s on every command, under the
	// command's own timeout, so a 3 s command could time out without having run.
	@Test
	fun `a shell command neither waits for the setsid check nor counts it against its timeout`() {
		assumeHostTools()
		val terminalSetsid = executableSetsid()
		val checking = CountDownLatch(1)
		val release = CountDownLatch(1)
		val check = { _: String ->
			checking.countDown()
			release.await()
			true
		}
		val service = serviceFinding { ProcessGroups.android(terminalSetsid, check) { thread(isDaemon = true) { it.run() } } }
		try {
			val started = System.nanoTime()
			val result = service.executeCommand(shell("echo ran"), 1_000).awaitWithin(5_000)
			val tookMs = (System.nanoTime() - started) / 1_000_000

			assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue()
			assertThat((result as CommandResult.Success).stdout).isEqualTo("ran\n")
			assertThat(tookMs).isLessThan(1_000)
		} finally {
			release.countDown()
		}
	}

	@Test
	fun `the setsid check runs off the command's path, one at a time`() {
		val terminalSetsid = executableSetsid()
		val checks = AtomicInteger()
		val queued = mutableListOf<Runnable>()
		val finder =
			SetsidFinder(listOf(terminalSetsid.path), {
				checks.incrementAndGet()
				true
			}, { queued += it })

		repeat(3) { assertThat(finder.current()).isNull() }

		assertThat(checks.get()).isEqualTo(0)
		assertThat(queued).hasSize(1)
		queued.single().run()
		assertThat(finder.current()).isEqualTo(terminalSetsid.path)
		assertThat(checks.get()).isEqualTo(1)
	}

	@Test
	fun `a setsid known to run in place, or to fork, is checked once`() {
		val forking = forkingSetsid()
		val terminalSetsid = executableSetsid()
		val checks = mutableListOf<String>()
		val finder =
			SetsidFinder(listOf(forking.path, terminalSetsid.path), {
				checks += it
				it == terminalSetsid.path
			}, { it.run() })

		repeat(3) { assertThat(finder.current()).isEqualTo(terminalSetsid.path) }

		assertThat(checks).containsExactly(forking.path, terminalSetsid.path).inOrder()
	}

	@Test
	fun `finding no setsid that runs in place is kept`() {
		val forking = forkingSetsid()
		val checks = AtomicInteger()
		val finder =
			SetsidFinder(listOf(forking.path), {
				checks.incrementAndGet()
				false
			}, { it.run() })

		repeat(3) { assertThat(finder.current()).isNull() }

		assertThat(checks.get()).isEqualTo(1)
	}

	// For example, the check timed out on a busy device: it must not leave every later command without
	// a group of its own until the app restarts, nor be run again for ever.
	@Test
	fun `a check that could not tell is tried again, a few times at most`() {
		val terminalSetsid = executableSetsid()
		val checks = AtomicInteger()
		val finder =
			SetsidFinder(listOf(terminalSetsid.path), {
				checks.incrementAndGet()
				null
			}, { it.run() })

		repeat(SetsidFinder.MAX_UNSURE_CHECKS + 3) { assertThat(finder.current()).isNull() }

		assertThat(checks.get()).isEqualTo(SetsidFinder.MAX_UNSURE_CHECKS)
	}

	@Test
	fun `a check that could not tell, then could, finds the setsid`() {
		val terminalSetsid = executableSetsid()
		val answers = ArrayDeque(listOf(null, true))
		val finder = SetsidFinder(listOf(terminalSetsid.path), { answers.removeFirst() }, { it.run() })

		assertThat(finder.current()).isNull()
		assertThat(finder.current()).isEqualTo(terminalSetsid.path)
		assertThat(answers).isEmpty()
	}

	// Through ProcessGroups.android: the Terminal installs its setsid at first-run setup, possibly
	// after plugins have run commands.
	@Test
	fun `the Terminal's setsid is checked once it can run`() {
		val terminalSetsid = File(tmp.newFolder("terminal-bin"), "setsid")
		val checks = AtomicInteger()
		val check = { _: String ->
			checks.incrementAndGet()
			true
		}
		val direct = { it: Runnable -> it.run() }

		assertThat(ProcessGroups.android(terminalSetsid, check, direct).isolate(listOf(SH), null).grouped).isFalse()
		assertThat(checks.get()).isEqualTo(0)

		terminalSetsid.writeText("#!/bin/sh\nexec \"\$@\"\n")
		terminalSetsid.setExecutable(true)

		assertThat(ProcessGroups.android(terminalSetsid, check, direct).isolate(listOf(SH), null).grouped).isTrue()
		assertThat(ProcessGroups.android(terminalSetsid, check, direct).isolate(listOf(SH), null).grouped).isTrue()
		assertThat(checks.get()).isEqualTo(1)
	}

	@Test
	fun `setsid is used when it runs the command in place`() {
		assumeHostTools()

		assertThat(ProcessGroups.runsInPlace(SETSID, SH)).isTrue()
	}

	@Test
	fun `a missing setsid is not checked`() {
		val checks = AtomicInteger()
		val finder =
			SetsidFinder(listOf(File(tmp.root, "none").path), {
				checks.incrementAndGet()
				true
			}, { it.run() })

		assertThat(finder.current()).isNull()
		assertThat(checks.get()).isEqualTo(0)
	}

	// A directory passes canExecute(), and starting it fails: a check that could not run says nothing.
	@Test
	fun `a setsid that cannot be started gives no definite answer`() {
		assertThat(ProcessGroups.runsInPlace(tmp.newFolder("not-a-program").path, SH)).isNull()
	}

	// As Android 11's toybox setsid does: it forks, and its parent exits 0 at once.
	@Test
	fun `a setsid that forks is not used, so a command still runs to its end`() {
		assumeHostTools()
		val forking = forkingSetsid()
		val setsid = SetsidFinder(listOf(forking.path), { ProcessGroups.runsInPlace(it, SH) }, { it.run() }).current()
		val groups = ProcessGroups(setsid, ::hostSignal)

		val result = service(groups).executeCommand(shell("sleep 1; echo late"), 10_000).awaitWithin(10_000)

		assertThat(ProcessGroups.runsInPlace(forking.path, SH)).isFalse()
		assertThat(setsid).isNull()
		assertThat((result as CommandResult.Success).stdout).isEqualTo("late\n")
	}

	// Android 11 again, with the Terminal's util-linux setsid installed: commands still get a group.
	@Test
	fun `where the first setsid forks, the next one that runs commands in place is used`() {
		assumeHostTools()
		val candidates = listOf(forkingSetsid().path, File(tmp.root, "none").path, SETSID)
		val setsid = SetsidFinder(candidates, { ProcessGroups.runsInPlace(it, SH) }, { it.run() }).current()
		val groups = ProcessGroups(setsid, ::hostSignal)

		val execution = service(groups).executeCommand(shell(CHILDREN_SCRIPT), 300)
		val result = execution.awaitWithin(10_000)

		assertThat(setsid).isEqualTo(SETSID)
		assertThat((result as CommandResult.Failure).error).isEqualTo("Command timed out after 300ms")
		assertThat(goneWithin(pids(CHILDREN_SCRIPT_PIDS), 1_000)).isTrue()
	}

	/** A `setsid` that runs its command in place, as util-linux's does. */
	private fun executableSetsid() =
		File(tmp.newFolder(), "setsid").apply {
			writeText("#!/bin/sh\nexec \"\$@\"\n")
			setExecutable(true)
		}

	/** A `setsid` that forks and exits 0 at once, as Android 11's toybox one does. */
	private fun forkingSetsid() =
		File(tmp.root, "forking-setsid").apply {
			writeText("#!/bin/sh\n\"\$@\" &\nexit 0\n")
			setExecutable(true)
		}

	@Test
	fun `a stop that finds no group yet signals the group again once the command is killed`() {
		val sent = mutableListOf<String>()
		val process = FakeProcess { sent += "destroy" }
		val groups =
			ProcessGroups("/setsid") { pgid, signal ->
				sent += "$signal $pgid"
				// setsid runs, and the command starts a child, just after the TERM finds no group.
				signal != GroupSignal.TERM
			}

		runBlocking { groups.stop(process, grouped = true) }

		assertThat(sent).containsExactly("TERM ${FakeProcess.PID}", "destroy", "KILL ${FakeProcess.PID}").inOrder()
	}

	@Test
	fun `without setsid a timeout still stops the command itself`() {
		assumeHostTools()
		val noGroups = ProcessGroups(null) { _, _ -> throw AssertionError("signalled a group that does not exist") }

		val result = service(noGroups).executeCommand(shell("echo \$\$ >> \"\$1\"; exec sleep 600"), 300).awaitWithin(10_000)

		assertThat((result as CommandResult.Failure).error).isEqualTo("Command timed out after 300ms")
		assertThat(goneWithin(pids(1), 1_000)).isTrue()
	}

	@Test
	fun `a command runs in a group of its own when setsid can run it`() {
		val command = ProcessGroups("/setsid") { _, _ -> true }.isolate(listOf("sh", "-c", "true"), null)

		assertThat(command.grouped).isTrue()
		assertThat(command.command[0]).isEqualTo("/setsid")
		// The sh ProcessBuilder would run, found in the IDE's PATH rather than the command's.
		assertThat(File(command.command[1]).isAbsolute).isTrue()
		assertThat(File(command.command[1]).name).isEqualTo("sh")
		assertThat(command.command.drop(2)).containsExactly("-c", "true").inOrder()
	}

	@Test
	fun `a command with no such program is left to fail as before`() {
		val command = listOf("no-such-program-7178", "x")

		val isolated = ProcessGroups("/setsid") { _, _ -> true }.isolate(command, null)

		assertThat(isolated).isEqualTo(GroupedCommand(command, grouped = false))
	}

	/** Alive until destroyed, with its process id where [ProcessGroups] reads it on Android. */
	private class FakeProcess(
		private val onDestroy: () -> Unit,
	) : Process() {
		@JvmField
		val pid = PID
		private var alive = true

		override fun getOutputStream() = ByteArrayOutputStream()

		override fun getInputStream() = ByteArrayInputStream(ByteArray(0))

		override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))

		override fun waitFor() = 0

		override fun exitValue() = if (alive) throw IllegalThreadStateException("running") else 0

		override fun isAlive() = alive

		override fun destroy() {
			alive = false
			onDestroy()
		}

		override fun destroyForcibly(): Process {
			destroy()
			return this
		}

		companion object {
			const val PID = 4242
		}
	}

	private companion object {
		const val SETSID = "/usr/bin/setsid"
		const val KILL = "/usr/bin/kill"
		const val SH = "/bin/sh"

		/**
		 * Ends a script that leaves a child holding its output: the shell waits for `$2` to exist,
		 * which [releaseOnceReading] creates once the readers are in read(). A shell that exits first
		 * has its output drained and closed by [Process], which hides the child. Also ends once `$1`,
		 * which the script has written to, is deleted with the test's folder, so that a test failing
		 * first leaves no shell behind.
		 */
		const val WAIT_FOR_RELEASE = "until [ -e \"\$2\" ] || [ ! -e \"\$1\" ]; do sleep 0.01; done"
		val DEV_NULL = File("/dev/null")

		/** A child, a double-forked grandchild, the shell itself, and a child it waits for. */
		const val CHILDREN_SCRIPT =
			"sleep 600 & echo \$! >> \"\$1\"; (sleep 600 & echo \$! >> \"\$1\"); " +
				"echo \$\$ >> \"\$1\"; sleep 600 & echo \$! >> \"\$1\"; wait"
		const val CHILDREN_SCRIPT_PIDS = 4

		/** Writes its process id to `$1`, then sleeps. */
		const val STARTED_SLEEP = "echo \$\$ >> \"\$1\"; exec sleep 600"
	}
}
