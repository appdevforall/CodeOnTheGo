package org.appdevforall.cotg.quickbuild.domain.session

/**
 * Colorblind-safe presentation tone for the Quick Build toolbar icon.
 *
 * Status is never carried by color alone: each tone maps to a distinct icon shape as well as a
 * distinct color. The app module owns that drawable/color mapping because it needs a Context;
 * this type is the JVM-testable half.
 *
 * Only [ERROR] is colored as a failure - a tone the user cannot act on, or that resolves by itself
 * (a full rebuild during ordinary editing, a daemon respawn), must not read as one.
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
 * @return the tone for that status; [QuickBuildTone.READY] also covers a plain
 *   [QuickBuildStatus.Hidden], where the icon is present but no session is running.
 */
fun QuickBuildStatus.toTone(): QuickBuildTone =
	when (this) {
		// A failed START is a failure the user has to deal with - only a tap retries it - so
		// it must not settle back to the green bolt the moment the failure flash fades.
		is QuickBuildStatus.Hidden -> {
			if (lastStartFailed) QuickBuildTone.ERROR else QuickBuildTone.READY
		}

		is QuickBuildStatus.UpToDate -> {
			QuickBuildTone.READY
		}

		is QuickBuildStatus.Provisioning,
		is QuickBuildStatus.Building,
		-> {
			QuickBuildTone.BUILDING
		}

		// A rebaseline that failed and parked is not ordinary upcoming work: nothing moves
		// until the user acts, which is exactly what ERROR means here.
		is QuickBuildStatus.NeedsFullBuild -> {
			if (awaitingRetry) QuickBuildTone.ERROR else QuickBuildTone.SLOW
		}

		// A respawn that failed is not invisible work resolving itself: the compiler is down
		// until the user taps, which is exactly what ERROR means here.
		is QuickBuildStatus.Reconnecting -> {
			if (restartFailed) QuickBuildTone.ERROR else QuickBuildTone.RECONNECTING
		}

		is QuickBuildStatus.Failed -> {
			QuickBuildTone.ERROR
		}
	}
