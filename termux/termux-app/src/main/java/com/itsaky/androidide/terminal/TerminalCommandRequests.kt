package com.itsaky.androidide.terminal

import com.termux.terminal.TerminalSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Commands a plugin asked to run in a visible Terminal session.
 *
 * The caller [enqueue]s one and starts [com.itsaky.androidide.activities.TerminalActivity] with its
 * id; the activity [claim]s it and creates the session; the session clients report its exit through
 * [onSessionFinished].
 */
object TerminalCommandRequests {
	/** Intent extra carrying the id [enqueue] returned. */
	const val EXTRA_COMMAND_REQUEST_ID = "com.itsaky.androidide.terminal.COMMAND_REQUEST_ID"

	/**
	 * Echoes the command, then runs it, so the session shows what ran. The command is passed as `$1`
	 * rather than spliced into the script, so it needs no quoting.
	 */
	const val RUN_SCRIPT = "printf '$ %s\\n' \"$1\"; eval \"$1\""

	class Request internal constructor(
		val id: String,
		val command: String,
		val workingDirectory: String?,
		val sessionName: String,
		private val onExit: (exitCode: Int, transcript: String) -> Unit,
		private val onNotStarted: (reason: String) -> Unit,
	) {
		@Volatile
		internal var session: TerminalSession? = null

		@Volatile
		internal var cancelled = false

		internal fun exited(
			exitCode: Int,
			transcript: String,
		) = onExit(exitCode, transcript)

		/** Reports that the session for this request could not be created. */
		fun notStarted(reason: String) {
			requests.remove(id)
			onNotStarted(reason)
		}
	}

	// Every request until it ends, so a cancel that lands between claim and attach is not lost.
	private val requests = ConcurrentHashMap<String, Request>()
	private val pending = ConcurrentHashMap<String, Request>()
	private val running = ConcurrentHashMap<TerminalSession, Request>()

	fun enqueue(
		command: String,
		workingDirectory: String?,
		sessionName: String,
		onExit: (exitCode: Int, transcript: String) -> Unit,
		onNotStarted: (reason: String) -> Unit,
	): String {
		val id = UUID.randomUUID().toString()
		val request = Request(id, command, workingDirectory, sessionName, onExit, onNotStarted)
		requests[id] = request
		pending[id] = request
		return id
	}

	/**
	 * Removes and returns request [id], or null if it was already claimed or withdrawn. One-shot, so
	 * an activity recreated with the same intent does not run the command twice.
	 */
	fun claim(id: String): Request? = pending.remove(id)

	/** Withdraws request [id] if no session has claimed it yet. Returns true if it was withdrawn. */
	fun withdraw(id: String): Boolean = (pending.remove(id) != null).also { if (it) requests.remove(id) }

	/** Records that [session] runs [request]. Call on the main thread, before the session can exit. */
	fun attach(
		request: Request,
		session: TerminalSession,
	) {
		request.session = session
		running[session] = request
		if (request.cancelled) session.finishIfRunning()
	}

	/** Kills the command of request [id], whether or not its session has started. */
	fun cancel(id: String) {
		val request = requests[id] ?: return
		request.cancelled = true
		if (pending.remove(id) != null) requests.remove(id)
		// attach reads cancelled after setting session, so one side or the other kills it.
		request.session?.finishIfRunning()
	}

	/**
	 * Called by the session clients when [session] exits. Returns true if the session ran a plugin
	 * command; the caller then keeps it open so the user can read what ran.
	 */
	@JvmStatic
	fun onSessionFinished(session: TerminalSession): Boolean {
		val request = running.remove(session) ?: return false
		requests.remove(request.id)
		request.exited(session.exitStatus, TerminalTranscript.of(session))
		return true
	}
}
