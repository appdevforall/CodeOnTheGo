package org.appdevforall.cotg.quickbuild.domain

/**
 * Colorblind-safe presentation tone for the Quick Build toolbar icon (plan A2). Status
 * must never be carried by color alone, so each tone maps to both a distinct icon shape
 * AND a distinct color in the app module - this type is the pure, JVM-testable half of
 * that derivation; the app module owns the drawable resource / color attr mapping since
 * that needs a Context.
 */
enum class QuickBuildTone {
	/** Ready to build - no session, or a session sitting on a successful build. */
	READY,

	/** A build is running (provisioning or an active quick build). */
	BUILDING,

	/** Needs the user's attention - a failure, a required full rebuild, or a reconnect. */
	ATTENTION,
}

/** Derives the toolbar presentation tone from the status the session surface already exposes. */
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
