package org.appdevforall.cotg.quickbuild.service

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
	 */
	fun hasUsedQuickBuild(): Boolean

	/** Records that this project has now tapped Quick Build. */
	fun setHasUsedQuickBuild(used: Boolean)
}
