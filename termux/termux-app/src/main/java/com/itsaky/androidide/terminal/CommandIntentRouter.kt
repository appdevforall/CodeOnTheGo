package com.itsaky.androidide.terminal

import android.content.Intent
import com.termux.terminal.TerminalSession

/**
 * Carries a plugin's command from the launcher to the Terminal screen: the launcher puts the
 * command's id on the intent that opens the screen, and the screen hands the id back to [route].
 *
 * Every method must be called on the main thread, like [TerminalCommandRequests].
 */
class CommandIntentRouter internal constructor(
	private val requests: TerminalCommandRequests,
) {
	/** The id of the command [intent] asks to run, or null if it asks for none. */
	fun requestId(intent: Intent?): String? = intent?.getStringExtra(EXTRA_COMMAND_REQUEST_ID)

	/** Makes [intent] ask the Terminal screen to run command [id]. */
	fun putRequestId(
		intent: Intent,
		id: String,
	): Intent = intent.putExtra(EXTRA_COMMAND_REQUEST_ID, id)

	/**
	 * Runs command [id] in an idle session of its plugin, or a new one from [factory].
	 *
	 * @param screenClosing whether the screen asking is finishing; a session started now would run
	 *   the command with nothing on screen, so the command is withdrawn instead.
	 * @return the session it runs in, or null if it does not run, including when it already ran
	 *   (a recreated screen sees the same intent again) or was withdrawn.
	 */
	fun route(
		id: String,
		factory: TerminalSessionFactory,
		screenClosing: Boolean,
	): TerminalSession? {
		if (screenClosing) {
			requests.withdraw(id, TerminalStartFailure.TerminalClosed)
			return null
		}
		return requests.start(id, factory)
	}

	companion object {
		/** Intent extra carrying the command id. */
		const val EXTRA_COMMAND_REQUEST_ID = "com.itsaky.androidide.terminal.COMMAND_REQUEST_ID"

		/** The router the plugin launcher and the Terminal screen share. */
		@JvmField
		val shared = CommandIntentRouter(TerminalCommandRequests.shared)
	}
}
