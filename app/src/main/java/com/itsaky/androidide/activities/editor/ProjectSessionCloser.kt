package com.itsaky.androidide.activities.editor

import org.slf4j.LoggerFactory

/**
 * Runs an editor's project-session teardown at most once, and only for the session it opened.
 *
 * The teardown stops the language servers before it destroys the project: a stopped server can no
 * longer pick up the next project's indexing manager, and the indexes it still holds close quietly.
 * It runs from `onPause` while finishing, because `isFinishing` is reliable there and the next
 * editor may open a project before this one's `onDestroy`. A configuration change never runs it:
 * `isFinishing` is false then, and the servers must survive the recreation. `preDestroy` calls it
 * too, for a `finish()` that arrives while already paused.
 *
 * Servers, the project manager and Quick Build are process-wide, so the teardown is skipped when the
 * current session is not the one [record]ed, which means another editor already owns them. With
 * nothing recorded (the project never finished opening, or a recreated editor skipped it) it tears
 * down as before recording existed. The check only tells sessions apart when a new manager was
 * created between them.
 *
 * [record] runs on the project initialization callback's thread and [close] on the main thread, so
 * both hold the lock: a session recorded after the teardown would never be closed.
 *
 * @param currentSession Returns the current session without creating one.
 * @param teardown Stops the servers and destroys the project.
 */
internal class ProjectSessionCloser<S : Any>(
	private val currentSession: () -> S?,
	private val teardown: () -> Unit,
) {
	private companion object {
		private val log = LoggerFactory.getLogger(ProjectSessionCloser::class.java)
	}

	private var recorded: S? = null
	private var tornDown = false

	/**
	 * Records the session [session] returns as the one this editor opened, and returns whether it did.
	 *
	 * Once the teardown has run it returns false without calling [session], since resolving a session
	 * may create one that nothing would close.
	 */
	@Synchronized
	fun record(session: () -> S): Boolean {
		if (tornDown) return false
		recorded = session()
		return true
	}

	/** Tears down when nothing was recorded or this editor still owns the current session. */
	@Synchronized
	fun close() {
		if (recorded == null || currentSession() === recorded) runTeardown() else skip()
	}

	private fun runTeardown() {
		if (tornDown) return
		tornDown = true
		teardown()
	}

	private fun skip() {
		if (!tornDown) log.info("Skipping project teardown: this editor does not own the current session")
	}
}
