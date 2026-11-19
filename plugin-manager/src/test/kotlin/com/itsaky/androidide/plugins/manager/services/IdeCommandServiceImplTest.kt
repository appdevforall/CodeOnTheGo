package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IdeCommandServiceImplTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun resultFor(workingDirectory: String): CommandResult =
		resultFor(CommandSpec.ShellCommand("true", workingDirectory = workingDirectory))

	private fun resultFor(spec: CommandSpec): CommandResult {
		val projectRoot = tmp.newFolder("project")
		val service =
			IdeCommandServiceImpl(
				pluginId = "test.plugin",
				permissions = setOf(PluginPermission.SYSTEM_COMMANDS),
				projectRootProvider = { projectRoot },
				appFilesDir = tmp.newFolder("files"),
			)
		val execution = service.executeCommand(spec, 5_000)
		return runBlocking { execution.await() }
	}

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
}
