package com.itsaky.androidide.quickbuild

/**
 * One-shot handoff from [QuickBuildBenchActivity] to the editor: the bench activity records
 * the project it is about to open, and [com.itsaky.androidide.activities.editor.ProjectHandlerActivity]
 * claims it exactly once - when that project finishes initializing - to fire the first
 * Quick Build tap in place of the human's lightning-bolt tap.
 *
 * Benchmark-only (both the experiments and qbbench flags gate every writer/reader), so a
 * process-global single slot is sufficient: there is never more than one pending bench
 * autostart in flight. Paths stored and claimed are canonical, so the match is exact.
 */
object QuickBuildBenchAutostart {
	@Volatile
	var pendingProjectPath: String? = null

	/**
	 * Returns true and clears the slot iff [projectPath] matches the pending path. A
	 * non-matching project (or no pending autostart) leaves the slot untouched and returns
	 * false, so an unrelated project open never consumes the latch.
	 */
	@Synchronized
	fun claim(projectPath: String): Boolean {
		if (pendingProjectPath == projectPath) {
			pendingProjectPath = null
			return true
		}
		return false
	}
}
