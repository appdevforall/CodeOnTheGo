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
}
