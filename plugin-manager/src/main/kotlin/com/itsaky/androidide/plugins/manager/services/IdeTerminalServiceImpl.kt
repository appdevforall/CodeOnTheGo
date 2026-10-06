package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.TermuxProcessEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Opens a command in a new, visible Terminal session. Implemented by the app module, which owns
 * the Terminal screen; see [IdeTerminalServiceImpl.setSessionLauncher].
 */
fun interface TerminalSessionLauncher {
	/**
	 * Runs [command] with bash in a new Terminal session named [sessionName], in [workingDirectory]
	 * (the default home directory when null), and calls [onResult] at most once: with
	 * [TerminalCommandResult.Completed] when the command exits, or [TerminalCommandResult.NotStarted]
	 * if the Terminal could not run it. Not called after the returned function kills the command.
	 *
	 * @return a function that kills the command.
	 */
	fun launch(
		command: String,
		workingDirectory: File?,
		sessionName: String,
		onResult: (TerminalCommandResult) -> Unit,
	): () -> Unit
}

class IdeTerminalServiceImpl(
	private val pluginId: String,
	private val permissions: Set<PluginPermission>,
	private val projectRootProvider: () -> File?,
	private val appFilesDir: File,
	private val bashProvider: () -> File? = { Environment.BASH_SHELL },
	private val launcherProvider: () -> TerminalSessionLauncher? = { sessionLauncher },
) : IdeTerminalService {
	override suspend fun isTerminalReady(): Boolean =
		withContext(Dispatchers.IO) {
			val bash = bashProvider()?.takeIf { it.canExecute() } ?: return@withContext false
			runCatching {
				val process =
					ProcessBuilder(bash.absolutePath, "-c", "exit 0")
						.redirectErrorStream(true)
						.apply { TermuxProcessEnvironment.applyTo(environment(), appFilesDir) }
						.start()
				try {
					process.waitFor(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0
				} finally {
					// Also on an interrupt, which would otherwise leave bash running.
					if (process.isAlive) process.destroyForcibly()
				}
			}.onFailure { log.warn("Terminal readiness probe failed", it) }
				.getOrDefault(false)
		}

	override suspend fun runInTerminal(
		command: String,
		workingDirectory: String?,
	): TerminalCommandResult {
		if (PluginPermission.SYSTEM_COMMANDS !in permissions) {
			throw SecurityException("Plugin $pluginId does not have SYSTEM_COMMANDS permission")
		}
		val workDir = resolvePluginWorkingDirectory(pluginId, projectRootProvider(), workingDirectory)
		if (workDir != null && !workDir.isDirectory) {
			return TerminalCommandResult.NotStarted("Working directory does not exist: $workDir")
		}
		if (bashProvider()?.canExecute() != true) {
			return TerminalCommandResult.NotStarted("The terminal environment is not installed")
		}
		val launcher = launcherProvider() ?: return TerminalCommandResult.NotStarted("The Terminal is not available")

		return suspendCancellableCoroutine { continuation ->
			val kill = launcher.launch(command, workDir, pluginId) { continuation.resume(it) }
			continuation.invokeOnCancellation { kill() }
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(IdeTerminalServiceImpl::class.java)

		private const val READY_TIMEOUT_MS = 5_000L

		@Volatile
		private var sessionLauncher: TerminalSessionLauncher? = null

		/** Set by the app module during initialization. */
		fun setSessionLauncher(launcher: TerminalSessionLauncher) {
			sessionLauncher = launcher
		}
	}
}
