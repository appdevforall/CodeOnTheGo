package org.appdevforall.cotg.quickbuild.service

/**
 * Per-project persisted Quick Build mode state (same-app-id design contract, sections
 * 1 and 6). Implemented over CoGo's project preferences in the app module - never the
 * user's gradle files - and scoped to the currently open project. An episode is the
 * span from a confirmed clobber warning to the Standard Run that restores the real
 * app; its pinned versionCode and confirmation persist across sessions and CoGo
 * restarts so the warning shows once per episode, not once per session.
 */
interface QuickBuildModeStore {
	/** The per-project OPT-IN toggle: same-app-id mode requested for this project. */
	fun isSameAppIdEnabled(): Boolean

	fun setSameAppIdEnabled(enabled: Boolean)

	/** The episode's pinned versionCode override, or null when no episode is active. */
	fun pinnedVersionCode(): Int?

	fun setPinnedVersionCode(versionCode: Int?)

	/** True when this episode's clobber warning was confirmed. */
	fun isClobberConfirmed(): Boolean

	fun setClobberConfirmed(confirmed: Boolean)

	/** The real applicationId the confirmed episode covers, or null. */
	fun episodeRealApplicationId(): String?

	fun setEpisodeRealApplicationId(applicationId: String?)

	/**
	 * True while a Standard Run restore still needs a version downgrade: the episode
	 * ended (the test app sits at the high pinned versionCode) but the real app has not
	 * yet reinstalled below it. Persisted per-project so the downgrade request survives a
	 * CoGo restart between a cancelled restore and its retry (design contract section 4);
	 * without it a retried restore is rejected as a downgrade with no recovery. Set false
	 * on the next mode entry or when the mode is disabled.
	 */
	fun isRestoreDowngradePending(): Boolean

	fun setRestoreDowngradePending(pending: Boolean)

	/**
	 * True once this project has tapped Quick Build at least once (plan P7's eager
	 * prewarm gate: no warm-up for a project with no signal it will ever be used).
	 */
	fun hasUsedQuickBuild(): Boolean

	fun setHasUsedQuickBuild(used: Boolean)
}
