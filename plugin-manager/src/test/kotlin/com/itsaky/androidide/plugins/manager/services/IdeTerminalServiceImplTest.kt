package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import kotlinx.coroutines.async
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

	private class FakeLauncher(
		private val result: TerminalCommandResult? = null,
	) : TerminalSessionLauncher {
		val launches = mutableListOf<Triple<String, File?, String>>()
		var killed = false

		override fun launch(
			command: String,
			workingDirectory: File?,
			sessionName: String,
			onResult: (TerminalCommandResult) -> Unit,
		): () -> Unit {
			launches += Triple(command, workingDirectory, sessionName)
			result?.let(onResult)
			return { killed = true }
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
	) = IdeTerminalServiceImpl(
		pluginId = "test.plugin",
		permissions = permissions,
		projectRootProvider = { projectRoot },
		appFilesDir = tmp.root,
		bashProvider = { bash },
		launcherProvider = { launcher },
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
	fun cancellingTheCallerKillsTheCommand() {
		// No result: the command is still running when the caller gives up.
		val launcher = FakeLauncher(result = null)

		runBlocking {
			val run = async { service(launcher = launcher).runInTerminal("sleep 100") }
			while (launcher.launches.isEmpty()) yield()
			run.cancel()
			run.join()
		}

		assertThat(launcher.killed).isTrue()
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

		assertThat(launcher.killed).isTrue()
	}
}
