package org.appdevforall.cotg.quickbuild.service.session

/**
 * Remembers what the currently open project has done with Quick Build across CoGo runs.
 *
 * Backed by CoGo's project preferences in the app module, never the user's gradle files.
 */
interface QuickBuildHistoryStore {
	/**
	 * True once this project has tapped Quick Build at least once. Recorded for
	 * analytics; the eager prebuild does not gate on it (see
	 * [QuickBuildSessionManager.prebuild]).
	 *
	 * @return true when a tap was recorded in this or an earlier CoGo run
	 */
	fun hasUsedQuickBuild(): Boolean

	/**
	 * Records that this project has now tapped Quick Build.
	 *
	 * @param used the value to persist; callers only ever set it true, since nothing
	 *   un-taps a project
	 */
	fun setHasUsedQuickBuild(used: Boolean)
}
