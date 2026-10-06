package com.itsaky.androidide.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.plugins.manager.services.LaunchedTerminalCommand
import com.itsaky.androidide.plugins.manager.services.TerminalSessionLauncher
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import com.itsaky.androidide.terminal.AgentRunner
import com.itsaky.androidide.terminal.CommandIntentRouter
import com.itsaky.androidide.terminal.CommandState
import com.itsaky.androidide.terminal.TerminalCommandListener
import com.itsaky.androidide.terminal.TerminalCommandRequests
import com.itsaky.androidide.terminal.TerminalStartFailure
import com.itsaky.androidide.utils.applyMultiWindowFlags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Runs a plugin's command in one of its Terminal sessions, through the same activity the Terminal
 * sidebar action opens. [TerminalCommandRequests] is only touched on the main thread.
 */
internal class PluginTerminalLauncher(
	private val runner: AgentRunner = AgentRunner.termux,
	private val requests: TerminalCommandRequests = TerminalCommandRequests.shared,
	private val router: CommandIntentRouter = CommandIntentRouter.shared,
	private val foregroundActivity: () -> Activity?,
) : TerminalSessionLauncher {
	private val mainHandler = Handler(Looper.getMainLooper())

	override suspend fun launch(
		command: String,
		workingDirectory: File?,
		pluginId: String,
		sessionLabel: String,
	): LaunchedTerminalCommand {
		val workDir = workingDirectory?.absolutePath
		val id = withContext(Dispatchers.IO) { runner.prepare(command, workDir) }
		// Nothing suspends from here on, so a command handed to the Terminal is always returned.
		val launched = Launched(id)
		mainHandler.post { open(id, workDir, pluginId, sessionLabel, launched) }
		return launched
	}

	override suspend fun read(
		pluginId: String,
		sessionName: String,
	): TerminalCommandResult? = onMain { requests.read(pluginId, sessionName)?.toResult() }

	private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

	private fun open(
		id: String,
		workingDirectory: String?,
		pluginId: String,
		sessionLabel: String,
		listener: TerminalCommandListener,
	) {
		requests.enqueue(id, workingDirectory, pluginId, listener, sessionLabel)

		// Android blocks activity starts from the background, so a plugin can only open the
		// Terminal while the IDE is on screen.
		val activity = foregroundActivity()
		if (activity == null) {
			requests.withdraw(id, TerminalStartFailure.NotInForeground)
			return
		}

		val intent = router.putRequestId(Intent(activity, TerminalActivity::class.java), id).applyMultiWindowFlags(activity)
		try {
			activity.startActivity(intent)
		} catch (e: Exception) {
			logger.error("Failed to open the Terminal for a plugin command", e)
			requests.withdraw(id, TerminalStartFailure.TerminalNotOpened)
			return
		}

		// The activity can fail to start or finish before its service connects; without this the
		// plugin would wait forever for a session that never comes.
		// Posted with the listener as its token, which removes it once the Terminal reports back.
		HandlerCompat.postDelayed(
			mainHandler,
			{ requests.withdraw(id, TerminalStartFailure.TerminalDidNotOpen) },
			listener,
			OPEN_TIMEOUT_MS,
		)
	}

	/** Command [id] as the plugin service sees it, fed by the Terminal's reports on the main thread. */
	private inner class Launched(
		private val id: String,
	) : LaunchedTerminalCommand,
		TerminalCommandListener {
		override val started = CompletableDeferred<Unit>()
		override val result = CompletableDeferred<TerminalCommandResult>()

		// Posted after the open in launch, so the command is queued by the time it is cancelled.
		override fun interrupt() {
			mainHandler.post { requests.cancel(id) }
		}

		override suspend fun snapshot(): TerminalCommandResult.Running? = onMain { requests.snapshot(id)?.toResult() }

		override fun onStarted(sessionName: String) {
			cancelOpenTimeout()
			started.complete(Unit)
		}

		override fun onExited(
			exitCode: Int,
			output: String,
		) {
			result.complete(TerminalCommandResult.Completed(exitCode, output))
		}

		override fun onNotStarted(reason: TerminalStartFailure) {
			cancelOpenTimeout()
			result.complete(TerminalCommandResult.NotStarted(reason.message))
		}

		private fun cancelOpenTimeout() = mainHandler.removeCallbacksAndMessages(this)
	}

	private companion object {
		private val logger = LoggerFactory.getLogger(PluginTerminalLauncher::class.java)

		const val OPEN_TIMEOUT_MS = 15_000L

		fun CommandState.Running.toResult() = TerminalCommandResult.Running(sessionName, output)

		fun CommandState.toResult(): TerminalCommandResult =
			when (this) {
				is CommandState.Running -> toResult()
				is CommandState.Exited -> TerminalCommandResult.Completed(exitCode, output)
			}
	}
}
