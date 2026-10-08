package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.TermuxProcessEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs commands in a plugin's visible Terminal sessions. Implemented by the app module, which owns
 * the Terminal screen; see [IdeTerminalServiceImpl.setSessionLauncher].
 */
interface TerminalSessionLauncher {
	/**
	 * Hands [command] to the Terminal, to run with bash in [workingDirectory] (the default home
	 * directory when null) in an idle session of plugin [pluginId], or a new one when all are busy.
	 * A new session is named after [sessionLabel]. If cancelled before it returns, the command never runs.
	 */
	suspend fun launch(
		command: String,
		workingDirectory: File?,
		pluginId: String,
		sessionLabel: String,
	): LaunchedTerminalCommand

	/**
	 * Command [commandId] of plugin [pluginId]: [TerminalCommandResult.Running] while it runs,
	 * [TerminalCommandResult.Completed] once it exits, or null when it is not a command of that
	 * plugin the Terminal still knows.
	 */
	suspend fun read(
		pluginId: String,
		commandId: String,
	): TerminalCommandResult?

	/**
	 * Interrupts command [commandId] of plugin [pluginId] with Ctrl-C if it runs, whichever caller
	 * launched it.
	 *
	 * @return the command's result, which completes once it exits, or null when it does not run.
	 */
	suspend fun interrupt(
		pluginId: String,
		commandId: String,
	): Deferred<TerminalCommandResult>?
}

/** A command [TerminalSessionLauncher.launch] handed to the Terminal. */
interface LaunchedTerminalCommand {
	/** Completes once a Terminal session takes the command. */
	val started: Deferred<Unit>

	/** [TerminalCommandResult.Completed] once the command exits, or [TerminalCommandResult.NotStarted]. */
	val result: Deferred<TerminalCommandResult>

	/**
	 * Interrupts the command with Ctrl-C, and ends its session if it has not exited a few seconds
	 * later; withdraws it if no session has taken it yet.
	 */
	fun interrupt()

	/** The session the command runs in and its output so far; null before a session takes it or once it exited. */
	suspend fun snapshot(): TerminalCommandResult.Running?
}

/**
 * @param sessionLabel what the plugin's Terminal sessions are named after, e.g. "AI Core 1"; the
 *   plugin's display name, which reads better on the Terminal screen than its id.
 */
class IdeTerminalServiceImpl(
	private val pluginId: String,
	private val sessionLabel: String = pluginId,
	private val permissions: Set<PluginPermission>,
	private val projectRootProvider: () -> File?,
	private val appFilesDir: File,
	private val bashProvider: () -> File? = { Environment.BASH_SHELL },
	private val launcherProvider: () -> TerminalSessionLauncher? = { sessionLauncher },
) : IdeTerminalService {
	// Ended on unload: a command, or a caller waiting from a scope the plugin never cancels, would
	// outlive it. A command leaves [running] once interrupted, so it never gets a second Ctrl-C.
	private val running = ConcurrentHashMap.newKeySet<LaunchedTerminalCommand>()
	private val waiting = ConcurrentHashMap.newKeySet<Job>()

	// Set by cancelAll; a run still in its IO checks is not in [running] yet and must not launch after.
	@Volatile
	private var closed = false

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
		waitMillis: Long,
	): TerminalCommandResult {
		requireSystemCommands()
		// Disk checks off the caller's thread: plugins call this from main-thread coroutines.
		val (workDir, notStarted) =
			withContext(Dispatchers.IO) {
				val dir = resolvePluginWorkingDirectory(pluginId, projectRootProvider(), workingDirectory)
				dir to
					when {
						dir != null && !dir.isDirectory -> "Working directory does not exist: $dir"
						bashProvider()?.canExecute() != true -> "The terminal environment is not installed"
						else -> null
					}
			}
		if (notStarted != null) return TerminalCommandResult.NotStarted(notStarted)
		val launcher = launcherProvider() ?: return TerminalCommandResult.NotStarted("The Terminal is not available")

		if (closed) throw CancellationException(UNLOADED)
		val launched = launcher.launch(command, workDir, pluginId, sessionLabel)
		running += launched
		launched.result.invokeOnCompletion { running -= launched }
		// Its own scope, so cancelAll cancels this wait and not the rest of the caller.
		return coroutineScope {
			val wait = coroutineContext.job
			waiting += wait
			try {
				// Checked after joining [running] and [waiting]: cancelAll either sees this run or this run sees closed.
				if (closed) throw CancellationException(UNLOADED)
				withTimeoutOrNull(waitMillis.coerceAtLeast(0)) { launched.result.await() } ?: launched.stillRunning()
			} catch (e: CancellationException) {
				launched.interruptOnce()
				throw e
			} finally {
				waiting -= wait
			}
		}
	}

	/**
	 * Interrupts this plugin's terminal commands, also those [runInTerminal] already returned as
	 * running, and ends the sessions of those that ignore it; each caller still waiting is
	 * cancelled, and so is any later call. Called on unload.
	 */
	fun cancelAll() {
		closed = true
		waiting.forEach { it.cancel() }
		running.forEach { it.interruptOnce() }
	}

	private fun LaunchedTerminalCommand.interruptOnce() {
		if (running.remove(this)) interrupt()
	}

	override suspend fun readCommand(commandId: String): TerminalCommandResult? {
		requireSystemCommands()
		return launcherProvider()?.read(pluginId, commandId)
	}

	override suspend fun stopCommand(
		commandId: String,
		waitMillis: Long,
	): TerminalCommandResult? {
		requireSystemCommands()
		val launcher = launcherProvider() ?: return null
		// Through the launcher, not [running]: the command may come from a cancelled caller, or from
		// this plugin before it was reloaded.
		val result = launcher.interrupt(pluginId, commandId) ?: return launcher.read(pluginId, commandId)
		return withTimeoutOrNull(waitMillis.coerceAtLeast(0)) { result.await() }
			?: launcher.read(pluginId, commandId)
	}

	private fun requireSystemCommands() {
		if (PluginPermission.SYSTEM_COMMANDS !in permissions) {
			throw SecurityException("Plugin $pluginId does not have SYSTEM_COMMANDS permission")
		}
	}

	/**
	 * The result once the wait is over: [TerminalCommandResult.Running] as soon as a session holds
	 * the command, or its result if that comes first.
	 */
	private suspend fun LaunchedTerminalCommand.stillRunning(): TerminalCommandResult {
		// The launcher reports NotStarted if no session ever takes the command, so this wait ends.
		val ended =
			select<TerminalCommandResult?> {
				result.onAwait { it }
				started.onAwait { null }
			}
		// A null snapshot means the command exited in between; its result is then on its way.
		return ended ?: snapshot() ?: result.await()
	}

	companion object {
		private val log = LoggerFactory.getLogger(IdeTerminalServiceImpl::class.java)

		private const val READY_TIMEOUT_MS = 5_000L

		private const val UNLOADED = "Plugin unloaded"

		@Volatile
		private var sessionLauncher: TerminalSessionLauncher? = null

		/** Set by the app module during initialization. */
		fun setSessionLauncher(launcher: TerminalSessionLauncher) {
			sessionLauncher = launcher
		}
	}
}
