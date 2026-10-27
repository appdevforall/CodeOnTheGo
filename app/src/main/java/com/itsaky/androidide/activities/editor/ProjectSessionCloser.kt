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
 * current session is not the one [record]ed, which means another editor already owns them. The
 * check only tells sessions apart when a new manager was created between them.
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

	/** Records [session] as the one this editor opened; ignored once the teardown has run. */
	fun record(session: S) {
		if (tornDown) return
		recorded = session
	}

	/** Tears down when nothing was recorded (as before recording existed) or this editor still owns the session. */
	fun closeOnPause() {
		if (recorded == null || ownsCurrent()) runTeardown() else skip("pause")
	}

	/** Tears down only when this editor recorded a session and still owns it. */
	fun closeOnDestroy() {
		if (recorded != null && ownsCurrent()) runTeardown() else skip("destroy")
	}

	private fun ownsCurrent() = currentSession() === recorded

	private fun runTeardown() {
		if (tornDown) return
		tornDown = true
		teardown()
	}

	private fun skip(caller: String) {
		if (!tornDown) log.info("Skipping project teardown on {}: this editor does not own the current session", caller)
	}
}
