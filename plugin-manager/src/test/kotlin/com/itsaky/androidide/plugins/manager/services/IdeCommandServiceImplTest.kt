package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.CommandExecution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

class IdeCommandServiceImplTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val projectRoot by lazy { tmp.newFolder("project") }
	private val filesDir by lazy { tmp.newFolder("files") }
	private val pidFile by lazy { File(tmp.root, "pids") }

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

	private fun service(processGroups: ProcessGroups = hostGroups) =
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
		val execution = service(ProcessGroups.android).executeCommand(spec, 5_000)
		return runBlocking { execution.await() }
	}

	/** `sh -c [script]`, where `$1` is [pidFile] for the script to write the ids of its processes to. */
	private fun shell(script: String) = CommandSpec.ShellCommand("sh", listOf("-c", script, "sh", pidFile.path))

	private fun CommandExecution.awaitWithin(ms: Long): CommandResult = runBlocking { withTimeout(ms) { await() } }

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

	/** Threads still in a command's output reader. */
	private fun readerThreads(): List<String> {
		val reader = "${CommandExecutionImpl::class.java.name}\$readLines"
		return Thread
			.getAllStackTraces()
			.filterValues { frames -> frames.any { it.className.startsWith(reader) } }
			.keys
			.map { it.name }
	}

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
					ProcessBuilder(SETSID, "sh", "-c", script, "sh", pidFile.path).start().also { pids(2) }
				},
				grouped = true,
				processGroups = hostGroups,
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

		assertThat(goneWithin(pids, 1_000)).isTrue()
		assertThat(service.getRunningCommandCount()).isEqualTo(0)
	}

	@Test
	fun `a command that exits before its timeout is not timed out while a child holds its output`() {
		assumeHostTools()
		val script = "echo done; sleep 600 & echo \$! >> \"\$1\""
		// The output stays open past the 1 s limit, until the grace ends and the child is stopped.
		val execution =
			CommandExecutionImpl(
				"test",
				ProcessBuilder(SETSID, "sh", "-c", script, "sh", pidFile.path)::start,
				grouped = true,
				processGroups = hostGroups,
				timeoutMs = 1_000,
				outputGraceMs = 2_000,
			)
		execution.start {}

		val result = execution.awaitWithin(10_000)

		assertThat(result).isInstanceOf(CommandResult.Success::class.java)
		result as CommandResult.Success
		assertThat(result.stdout).isEqualTo("done\n")
		assertThat(result.stderr).isEmpty()
		assertThat(result.durationMs).isAtLeast(2_000)
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
		val started = System.currentTimeMillis()

		val result = service().executeCommand(shell("echo done; sleep 600 & echo \$! >> \"\$1\""), 60_000).awaitWithin(10_000)

		assertThat(System.currentTimeMillis() - started).isLessThan(CommandExecutionImpl.OUTPUT_GRACE_MS + 1_000)
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
		val timedOut = List(3) { service.executeCommand(shell("sleep 600"), 300) }
		assertThrows(IllegalStateException::class.java) { service.executeCommand(shell("true"), 300) }

		timedOut.forEach { it.awaitWithin(10_000) }
		val deadline = System.currentTimeMillis() + 1_000
		while (service.getRunningCommandCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
		val cancelled = List(3) { service.executeCommand(shell("sleep 600"), 60_000) }
		cancelled.forEach { service.cancelCommand(it.executionId) }

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
	fun `setsid is used when it runs the command in place`() {
		assumeHostTools()

		assertThat(ProcessGroups.usableSetsid(SETSID, SH)).isEqualTo(SETSID)
	}

	@Test
	fun `a missing setsid is not used`() {
		assertThat(ProcessGroups.usableSetsid(File(tmp.root, "none").path, SH)).isNull()
	}

	// As Android 11's toybox setsid does: it forks, and its parent exits 0 at once.
	@Test
	fun `a setsid that forks is not used, so a command still runs to its end`() {
		assumeHostTools()
		val forking =
			File(tmp.root, "setsid").apply {
				writeText("#!/bin/sh\n\"\$@\" &\nexit 0\n")
				setExecutable(true)
			}
		val groups = ProcessGroups(ProcessGroups.usableSetsid(forking.path, SH), ::hostSignal)

		val result = service(groups).executeCommand(shell("sleep 1; echo late"), 10_000).awaitWithin(10_000)

		assertThat((result as CommandResult.Success).stdout).isEqualTo("late\n")
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

	private companion object {
		const val SETSID = "/usr/bin/setsid"
		const val KILL = "/usr/bin/kill"
		const val SH = "/bin/sh"
		val DEV_NULL = File("/dev/null")

		/** A child, a double-forked grandchild, the shell itself, and a child it waits for. */
		const val CHILDREN_SCRIPT =
			"sleep 600 & echo \$! >> \"\$1\"; (sleep 600 & echo \$! >> \"\$1\"); " +
				"echo \$\$ >> \"\$1\"; sleep 600 & echo \$! >> \"\$1\"; wait"
		const val CHILDREN_SCRIPT_PIDS = 4
	}
}
