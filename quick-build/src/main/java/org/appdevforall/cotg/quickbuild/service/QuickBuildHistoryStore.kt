package org.appdevforall.cotg.quickbuild.service

/**
 * Per-project persisted Quick Build history (ADFA-4128). Backed by CoGo's project preferences
 * in the app module - never the user's gradle files - and scoped to the currently open project.
 * Currently just the eager-prewarm gate: whether this project has ever tapped Quick Build.
 */
interface QuickBuildHistoryStore {
	/**
	 * True once this project has tapped Quick Build at least once (plan P7's eager prewarm gate:
	 * no warm-up for a project with no signal it will ever be used).
	 */
	fun hasUsedQuickBuild(): Boolean

	fun setHasUsedQuickBuild(used: Boolean)
}
