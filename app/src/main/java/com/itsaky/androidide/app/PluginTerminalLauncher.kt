package com.itsaky.androidide.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.plugins.manager.services.TerminalSessionLauncher
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import com.itsaky.androidide.terminal.TerminalCommandRequests
import com.itsaky.androidide.utils.applyMultiWindowFlags
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Opens a plugin's command in a new Terminal session, through the same activity the Terminal
 * sidebar action opens.
 */
internal class PluginTerminalLauncher(
	private val foregroundActivity: () -> Activity?,
) : TerminalSessionLauncher {
	private val mainHandler = Handler(Looper.getMainLooper())

	override fun launch(
		command: String,
		workingDirectory: File?,
		sessionName: String,
		onResult: (TerminalCommandResult) -> Unit,
	): () -> Unit {
		// Android blocks activity starts from the background, so a plugin can only open the
		// Terminal while the IDE is on screen.
		val activity = foregroundActivity()
		if (activity == null) {
			onResult(TerminalCommandResult.NotStarted("Code On the Go is not in the foreground"))
			return {}
		}

		val requestId =
			TerminalCommandRequests.enqueue(
				command = command,
				workingDirectory = workingDirectory?.absolutePath,
				sessionName = sessionName,
				onExit = { exitCode, transcript -> onResult(TerminalCommandResult.Completed(exitCode, transcript)) },
				onNotStarted = { reason -> onResult(TerminalCommandResult.NotStarted(reason)) },
			)
		val intent =
			Intent(activity, TerminalActivity::class.java)
				.putExtra(TerminalCommandRequests.EXTRA_COMMAND_REQUEST_ID, requestId)
				.applyMultiWindowFlags(activity)
		try {
			activity.startActivity(intent)
		} catch (e: Exception) {
			logger.error("Failed to open the Terminal for a plugin command", e)
			if (TerminalCommandRequests.withdraw(requestId)) {
				onResult(TerminalCommandResult.NotStarted("The Terminal could not be opened: ${e.message}"))
			}
			return {}
		}

		// The activity can fail to start or finish before its service connects; without this the
		// plugin would wait forever for a session that never comes.
		mainHandler.postDelayed({
			if (TerminalCommandRequests.withdraw(requestId)) {
				onResult(TerminalCommandResult.NotStarted("The Terminal did not open"))
			}
		}, OPEN_TIMEOUT_MS)

		return { TerminalCommandRequests.cancel(requestId) }
	}

	private companion object {
		private val logger = LoggerFactory.getLogger(PluginTerminalLauncher::class.java)

		const val OPEN_TIMEOUT_MS = 15_000L
	}
}
