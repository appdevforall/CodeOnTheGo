package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IdeTerminalServiceImplTest {
	@get:Rule
	val tmp = TemporaryFolder()

	/**
	 * Hands out commands that report [result] if given, after starting if [started]; each snapshot
	 * takes the next of [snapshots], the last repeating. [read] answers from [commands], and
	 * [interrupt] from [busyCommands].
	 */
	private class FakeLauncher(
		private val result: TerminalCommandResult? = null,
		private val started: Boolean = result !is TerminalCommandResult.NotStarted,
		private val snapshots: List<TerminalCommandResult.Running?> = listOf(null),
		private val commands: Map<String, TerminalCommandResult> = emptyMap(),
		private val busyCommands: Map<String, CompletableDeferred<TerminalCommandResult>> = emptyMap(),
	) : TerminalSessionLauncher {
		val launches = mutableListOf<Triple<String, File?, String>>()
		val labels = mutableListOf<String>()
		val reads = mutableListOf<Pair<String, String>>()
		val commandInterrupts = mutableListOf<Pair<String, String>>()
		var interrupted = false
		var snapshotsTaken = 0
		private val results = mutableListOf<CompletableDeferred<TerminalCommandResult>>()

		/** Ends the last launched command with [result]. */
		fun complete(result: TerminalCommandResult) = results.last().complete(result)

		override suspend fun launch(
			command: String,
			workingDirectory: File?,
			pluginId: String,
			sessionLabel: String,
		): LaunchedTerminalCommand {
			launches += Triple(command, workingDirectory, pluginId)
			labels += sessionLabel
			return object : LaunchedTerminalCommand {
				override val started = CompletableDeferred<Unit>().apply { if (this@FakeLauncher.started) complete(Unit) }
				override val result =
					CompletableDeferred<TerminalCommandResult>().also {
						results += it
						this@FakeLauncher.result?.let(it::complete)
					}

				override fun interrupt() {
					interrupted = true
				}

				override suspend fun snapshot() = snapshots[minOf(snapshotsTaken++, snapshots.lastIndex)]
			}
		}

		override suspend fun read(
			pluginId: String,
			commandId: String,
		): TerminalCommandResult? {
			reads += pluginId to commandId
			return commands[commandId]
		}

		override suspend fun interrupt(
			pluginId: String,
			commandId: String,
		): CompletableDeferred<TerminalCommandResult>? {
			commandInterrupts += pluginId to commandId
			return busyCommands[commandId]
		}
	}

	private fun script(exitCode: Int): File =
		tmp.newFile().apply {
			writeText("#!/bin/sh\nexit $exitCode\n")
			setExecutable(true)
		}

	private fun service(
		bash: File? = script(0),
		launcher: TerminalSessionLauncher? = FakeLauncher(TerminalCommandResult.Completed(0, "$ ls")),
		projectRoot: File? = tmp.root,
		permissions: Set<PluginPermission> = setOf(PluginPermission.SYSTEM_COMMANDS),
		sessionLabel: String = "test.plugin",
		launcherProvider: () -> TerminalSessionLauncher? = { launcher },
	) = IdeTerminalServiceImpl(
		pluginId = "test.plugin",
		sessionLabel = sessionLabel,
		permissions = permissions,
		projectRootProvider = { projectRoot },
		appFilesDir = tmp.root,
		bashProvider = { bash },
		launcherProvider = launcherProvider,
	)

	private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

	@Test
	fun readyWhenBashRunsCleanly() {
		assertThat(await { service().isTerminalReady() }).isTrue()
	}

	@Test
	fun notReadyWhenBashFails() {
		assertThat(await { service(bash = script(1)).isTerminalReady() }).isFalse()
	}

	@Test
	fun notReadyWhenBashIsMissing() {
		assertThat(await { service(bash = File(tmp.root, "missing")).isTerminalReady() }).isFalse()
		assertThat(await { service(bash = null).isTerminalReady() }).isFalse()
	}

	@Test
	fun runsInTheProjectRootByDefaultAndReturnsTheTranscript() {
		val launcher = FakeLauncher(TerminalCommandResult.Completed(2, "$ ./check.sh\nboom"))

		val result = await { service(launcher = launcher).runInTerminal("./check.sh") }

		assertThat(result).isEqualTo(TerminalCommandResult.Completed(2, "$ ./check.sh\nboom"))
		assertThat(launcher.launches).containsExactly(Triple("./check.sh", tmp.root, "test.plugin"))
	}

	@Test
	fun relativeWorkingDirectoryIsTakenAgainstTheProjectRoot() {
		val sub = tmp.newFolder("app")
		val launcher = FakeLauncher(TerminalCommandResult.Completed(0, ""))

		await { service(launcher = launcher).runInTerminal("ls", workingDirectory = "app") }

		assertThat(launcher.launches.single().second).isEqualTo(sub.canonicalFile)
	}

	@Test(expected = SecurityException::class)
	fun workingDirectoryOutsideTheProjectIsRejected() {
		val project = tmp.newFolder("project")

		await { service(projectRoot = project).runInTerminal("ls", workingDirectory = tmp.root.absolutePath) }
	}

	@Test(expected = SecurityException::class)
	fun runningRequiresSystemCommands() {
		await { service(permissions = emptySet()).runInTerminal("ls") }
	}

	@Test
	fun missingWorkingDirectoryDoesNotStart() {
		val launcher = FakeLauncher()

		val result = await { service(launcher = launcher).runInTerminal("ls", workingDirectory = "nope") }

		assertThat(result).isInstanceOf(TerminalCommandResult.NotStarted::class.java)
		assertThat(launcher.launches).isEmpty()
	}

	@Test
	fun missingTerminalEnvironmentDoesNotStart() {
		val launcher = FakeLauncher()

		val result = await { service(bash = File(tmp.root, "missing"), launcher = launcher).runInTerminal("ls") }

		assertThat(result).isEqualTo(TerminalCommandResult.NotStarted("The terminal environment is not installed"))
		assertThat(launcher.launches).isEmpty()
	}

	@Test
	fun noLauncherDoesNotStart() {
		val result = await { service(launcher = null).runInTerminal("ls") }

		assertThat(result).isEqualTo(TerminalCommandResult.NotStarted("The Terminal is not available"))
	}

	@Test
	fun launcherRefusalIsReturned() {
		val launcher = FakeLauncher(TerminalCommandResult.NotStarted("Code On the Go is not in the foreground"))

		val result = await { service(launcher = launcher).runInTerminal("ls") }

		assertThat(result).isEqualTo(TerminalCommandResult.NotStarted("Code On the Go is not in the foreground"))
	}

	@Test
	fun cancellingTheCallerInterruptsTheCommand() {
		// No result: the command is still running when the caller gives up.
		val launcher = FakeLauncher(result = null)

		runBlocking {
			val run = async { service(launcher = launcher).runInTerminal("sleep 100") }
			while (launcher.launches.isEmpty()) yield()
			run.cancel()
			run.join()
		}

		assertThat(launcher.interrupted).isTrue()
	}

	@Test
	fun commandStillRunningAfterTheWaitIsReturnedAsRunning() {
		val running = TerminalCommandResult.Running("3f2a", "test.plugin 1", "$ npm start\nlistening on 3000")
		val launcher = FakeLauncher(result = null, snapshots = listOf(running))

		val result = await { service(launcher = launcher).runInTerminal("npm start", waitMillis = 50) }

		assertThat(result).isEqualTo(running)
		// Left running: returning is not stopping it.
		assertThat(launcher.interrupted).isFalse()
	}

	@Test
	fun commandThatExitsWhileTheSnapshotIsTakenReturnsItsResult() {
		// Started, then exited before the snapshot: the snapshot is null and the result follows.
		val launcher = FakeLauncher(result = null, snapshots = listOf(null))
		val completed = TerminalCommandResult.Completed(0, "done")

		val result =
			await {
				val service = service(launcher = launcher)
				coroutineScope {
					val run = async { service.runInTerminal("make", waitMillis = 0) }
					while (launcher.snapshotsTaken == 0) yield()
					launcher.complete(completed)
					run.await()
				}
			}

		assertThat(result).isEqualTo(completed)
	}

	@Test
	fun commandNoSessionTookIsWaitedForUntilItIsRefused() {
		// Neither started nor ended within the wait: the launcher's refusal is what ends it.
		val refusal = TerminalCommandResult.NotStarted("The Terminal did not open")
		val launcher = FakeLauncher(result = refusal, started = false)

		val result = await { service(launcher = launcher).runInTerminal("npm start", waitMillis = 0) }

		assertThat(result).isEqualTo(refusal)
		assertThat(launcher.snapshotsTaken).isEqualTo(0)
	}

	@Test
	fun commandThatExitsWithinTheWaitTakesNoSnapshot() {
		val launcher = FakeLauncher(TerminalCommandResult.Completed(0, "$ ls"))

		await { service(launcher = launcher).runInTerminal("ls", waitMillis = 1_000) }

		assertThat(launcher.snapshotsTaken).isEqualTo(0)
	}

	@Test
	fun readingACommandAsksForThisPluginsCommand() {
		val running = TerminalCommandResult.Running("3f2a", "test.plugin 1", "listening on 3000")
		val launcher = FakeLauncher(commands = mapOf("3f2a" to running))

		val result = await { service(launcher = launcher).readCommand("3f2a") }

		assertThat(result).isEqualTo(running)
		assertThat(launcher.reads).containsExactly("test.plugin" to "3f2a")
	}

	@Test
	fun readingAnUnknownCommandIsNull() {
		assertThat(await { service(launcher = FakeLauncher()).readCommand("nope") }).isNull()
	}

	@Test
	fun readingWithoutATerminalIsNull() {
		assertThat(await { service(launcher = null).readCommand("3f2a") }).isNull()
	}

	@Test(expected = SecurityException::class)
	fun readingRequiresSystemCommands() {
		await { service(permissions = emptySet()).readCommand("3f2a") }
	}

	@Test
	fun cancelAllKillsTheCommandAndCancelsItsCaller() {
		val launcher = FakeLauncher(result = null)
		val service = service(launcher = launcher)

		runBlocking {
			val run = async { service.runInTerminal("sleep 100") }
			while (launcher.launches.isEmpty()) yield()
			service.cancelAll()
			withTimeout(5_000) { run.join() }
			assertThat(run.isCancelled).isTrue()
		}

		assertThat(launcher.interrupted).isTrue()
	}

	@Test
	fun cancelAllInterruptsACommandReturnedAsRunning() {
		val running = TerminalCommandResult.Running("3f2a", "test.plugin 1", "$ sleep 100")
		val launcher = FakeLauncher(result = null, snapshots = listOf(running))
		val service = service(launcher = launcher)

		assertThat(await { service.runInTerminal("sleep 100", waitMillis = 0) }).isInstanceOf(TerminalCommandResult.Running::class.java)
		assertThat(launcher.interrupted).isFalse()
		service.cancelAll()

		assertThat(launcher.interrupted).isTrue()
	}

	@Test
	fun cancelAllLeavesAnExitedCommandAlone() {
		val launcher = FakeLauncher(TerminalCommandResult.Completed(0, "$ ls"))
		val service = service(launcher = launcher)

		await { service.runInTerminal("ls") }
		service.cancelAll()

		assertThat(launcher.interrupted).isFalse()
	}

	@Test
	fun sessionsAreNamedAfterThePluginsLabel() {
		val launcher = FakeLauncher(TerminalCommandResult.Completed(0, "$ ls"))

		await { service(launcher = launcher, sessionLabel = "AI Core").runInTerminal("ls") }

		assertThat(launcher.labels).containsExactly("AI Core")
	}

	@Test
	fun stopInterruptsTheCommandAndReturnsHowItExited() {
		val command = CompletableDeferred<TerminalCommandResult>()
		val launcher = FakeLauncher(busyCommands = mapOf("3f2a" to command))
		val service = service(launcher = launcher)

		val result =
			await {
				coroutineScope {
					val stop = async { service.stopCommand("3f2a") }
					while (launcher.commandInterrupts.isEmpty()) yield()
					command.complete(TerminalCommandResult.Completed(130, "PING\n^C"))
					stop.await()
				}
			}

		assertThat(result).isEqualTo(TerminalCommandResult.Completed(130, "PING\n^C"))
		assertThat(launcher.commandInterrupts).containsExactly("test.plugin" to "3f2a")
	}

	@Test
	fun stopReachesACommandThisInstanceDidNotLaunch() {
		// E.g. started before the plugin was reloaded, or by a caller that was cancelled.
		val command = CompletableDeferred<TerminalCommandResult>(TerminalCommandResult.Completed(130, "^C"))
		val launcher = FakeLauncher(busyCommands = mapOf("3f2a" to command))

		assertThat(await { service(launcher = launcher).stopCommand("3f2a") }).isEqualTo(TerminalCommandResult.Completed(130, "^C"))
		assertThat(launcher.commandInterrupts).containsExactly("test.plugin" to "3f2a")
	}

	@Test
	fun stopOfACommandThatIgnoresCtrlCReportsItStillRunning() {
		val running = TerminalCommandResult.Running("3f2a", "Test 1", "still here")
		val launcher = FakeLauncher(commands = mapOf("3f2a" to running), busyCommands = mapOf("3f2a" to CompletableDeferred()))

		assertThat(await { service(launcher = launcher).stopCommand("3f2a", waitMillis = 0) }).isEqualTo(running)
		assertThat(launcher.commandInterrupts).containsExactly("test.plugin" to "3f2a")
	}

	@Test
	fun stopOfAnExitedCommandOnlyReadsIt() {
		val exited = TerminalCommandResult.Completed(0, "done")
		val launcher = FakeLauncher(commands = mapOf("3f2a" to exited))

		assertThat(await { service(launcher = launcher).stopCommand("3f2a") }).isEqualTo(exited)
		assertThat(launcher.reads).containsExactly("test.plugin" to "3f2a")
	}

	@Test
	fun stopOfAnUnknownCommandIsNull() {
		assertThat(await { service(launcher = FakeLauncher()).stopCommand("nope") }).isNull()
	}

	@Test(expected = SecurityException::class)
	fun stopRequiresSystemCommands() {
		await { service(permissions = emptySet()).stopCommand("3f2a") }
	}

	@Test
	fun cancelAllDuringTheChecksStopsTheLaunch() {
		val launcher = FakeLauncher(result = null)
		lateinit var service: IdeTerminalServiceImpl
		// The launcher is looked up after the IO checks, just before launching: unload lands there.
		service =
			service(launcherProvider = {
				service.cancelAll()
				launcher
			})

		runBlocking {
			val run = async { service.runInTerminal("sleep 100") }
			withTimeout(5_000) { run.join() }
			assertThat(run.isCancelled).isTrue()
		}

		assertThat(launcher.launches).isEmpty()
	}

	@Test
	fun reopenAfterCancelAllRunsAgain() {
		// Disable then enable reuses the same service instance.
		val service = service()
		service.cancelAll()
		service.reopen()

		val result = await { service.runInTerminal("ls") }

		assertThat(result).isEqualTo(TerminalCommandResult.Completed(0, "$ ls"))
	}
}
