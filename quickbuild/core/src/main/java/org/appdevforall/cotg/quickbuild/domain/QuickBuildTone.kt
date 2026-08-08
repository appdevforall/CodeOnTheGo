package org.appdevforall.cotg.quickbuild.domain

/**
 * Colorblind-safe presentation tone for the Quick Build toolbar icon.
 *
 * Status is never carried by color alone: each tone maps to a distinct icon shape as well as
 * a distinct color. The app module owns that drawable/color mapping because it needs a
 * Context; this type is the JVM-testable half.
 *
 * Only [ERROR] is colored as a failure. A tone that the user cannot act on, or that resolves
 * by itself, must not read as one - a full rebuild during ordinary editing is normal, and a
 * daemon respawn is invisible work. Those had all been folded into one red icon, which said
 * "something broke" three times more often than anything had.
 */
enum class QuickBuildTone {
	/** Ready to build - no session, or a session sitting on a successful build. */
	READY,

	/** A build is running (provisioning or an active quick build). Tapping stops it. */
	BUILDING,

	/** The next build cannot take the fast path and will be a full one. Not a failure. */
	SLOW,

	/** The compile daemon is being respawned. Transient, resolves itself, nothing to do. */
	RECONNECTING,

	/** A failure the user has to deal with. */
	ERROR,
}

/**
 * Derives the toolbar tone from the status the session surface already exposes.
 *
 * @receiver the status currently rendered, so tone and status can never disagree.
 * @return the tone for that status; [QuickBuildTone.READY] also covers [QuickBuildStatus.Hidden],
 *   where the icon is present but no session is running.
 */
fun QuickBuildStatus.toTone(): QuickBuildTone =
	when (this) {
		QuickBuildStatus.Hidden,
		is QuickBuildStatus.UpToDate,
		-> QuickBuildTone.READY

		QuickBuildStatus.Provisioning,
		is QuickBuildStatus.Building,
		-> QuickBuildTone.BUILDING

		is QuickBuildStatus.NeedsFullBuild -> QuickBuildTone.SLOW

		is QuickBuildStatus.Reconnecting -> QuickBuildTone.RECONNECTING

		is QuickBuildStatus.Failed -> QuickBuildTone.ERROR
	}
