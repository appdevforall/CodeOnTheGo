package org.appdevforall.cotg.quickbuild.domain

/**
 * Colorblind-safe presentation tone for the Quick Build toolbar icon.
 *
 * Status is never carried by color alone: each tone maps to a distinct icon shape as well as
 * a distinct color. The app module owns that drawable/color mapping because it needs a
 * Context; this type is the JVM-testable half.
 */
enum class QuickBuildTone {
	/** Ready to build - no session, or a session sitting on a successful build. */
	READY,

	/** A build is running (provisioning or an active quick build). */
	BUILDING,

	/** Needs the user's attention - a failure, a required full rebuild, or a reconnect. */
	ATTENTION,
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

		is QuickBuildStatus.Failed,
		is QuickBuildStatus.NeedsFullBuild,
		is QuickBuildStatus.Reconnecting,
		-> QuickBuildTone.ATTENTION
	}
