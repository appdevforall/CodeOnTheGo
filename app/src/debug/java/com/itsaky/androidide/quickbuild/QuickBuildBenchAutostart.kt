package com.itsaky.androidide.quickbuild

/**
 * One-shot handoff from [QuickBuildBenchActivity] to the editor: the bench activity records
 * the project it is about to open (and which build the harness wants), and
 * [com.itsaky.androidide.activities.editor.ProjectHandlerActivity] claims it exactly once -
 * when that project finishes initializing - to fire the first build in place of the human's
 * tap: either the Quick Build lightning-bolt ([MODE_QUICK_BUILD]) or the standard Run
 * ([MODE_STANDARD], for the cold standard-build-vs-proxy-app-build comparison).
 *
 * Benchmark-only (both the experiments and qbbench flags gate every writer/reader), so a
 * process-global single slot is sufficient: there is never more than one pending bench
 * autostart in flight. Paths stored and claimed are canonical, so the match is exact.
 *
 * Debug-source-set only: a release APK ships no benchmark code at all.
 */
object QuickBuildBenchAutostart {
	/** Autostart the Quick Build lightning-bolt: the whole edit-to-running-app loop is in-app. */
	const val MODE_QUICK_BUILD = "quickbuild"

	/**
	 * Autostart the standard Run button, and stop at the build result: the install dialog is
	 * suppressed (see `ProjectHandlerActivity.onBuildStateChanged`) because an unattended run
	 * cannot answer it.
	 *
	 * So this arm measures the BUILD only. Quick Build's arm measures build, deploy and reload,
	 * so the two are not like for like from in-app numbers alone: whatever compares them has to
	 * add the standard arm's install and launch from outside this process.
	 */
	const val MODE_STANDARD = "standard"

	@Volatile
	var pendingProjectPath: String? = null

	@Volatile
	var pendingMode: String = MODE_QUICK_BUILD

	/**
	 * Returns the pending mode and clears the slot iff [projectPath] matches the pending
	 * path, else null. A non-matching project (or no pending autostart) leaves the slot
	 * untouched, so an unrelated project open never consumes the latch.
	 */
	@Synchronized
	fun claim(projectPath: String): String? {
		if (pendingProjectPath == projectPath) {
			pendingProjectPath = null
			return pendingMode
		}
		return null
	}
}
