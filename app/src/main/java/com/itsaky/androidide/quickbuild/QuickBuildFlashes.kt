package com.itsaky.androidide.quickbuild

import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure

/**
 * A transient flashbar to raise for a Quick Build status change.
 *
 * Resource ids rather than strings so the decision stays a pure JVM function - testable without
 * a Context - while the copy stays translatable.
 */
sealed interface QuickBuildFlash {
	/**
	 * A failure the user has to act on; the caller renders it in the error tone.
	 *
	 * @property text the string resource to show.
	 */
	data class Failure(
		@StringRes val text: Int,
	) : QuickBuildFlash

	/**
	 * A failure just cleared; the caller renders it in the success tone.
	 *
	 * @property text the string resource to show.
	 */
	data class Recovery(
		@StringRes val text: Int,
	) : QuickBuildFlash
}

/**
 * Decides which Quick Build status changes deserve a flashbar over the editor: a compile failure,
 * and the build that clears one. Not every successful build - a Quick Build lands on every save, so
 * flashing each would put a bar over the editor every few seconds.
 *
 * A class rather than a function because a build always sits between a status and the next one
 * (`Failed -> Building -> UpToDate`), so neither decision can be read off a (previous, current)
 * pair; remembering the failure last flashed answers both and keeps that state under test.
 */
class QuickBuildFlashes {
	/**
	 * The failure whose flashbar the user has already seen and which no build has cleared yet, or
	 * null when nothing is outstanding. Doubles as the repeat guard and as the arming flag for a
	 * recovery, because they are the same fact.
	 */
	private var flashedFailure: SessionFailure? = null

	/**
	 * The flashbar for a status change, or null to raise none.
	 *
	 * @param previous the status before this change; null on the first emission after subscribing.
	 * @param current the status now.
	 * @return the flashbar to raise, or null when the change is not news.
	 */
	fun next(
		previous: QuickBuildStatus?,
		current: QuickBuildStatus,
	): QuickBuildFlash? =
		when (val transition = quickBuildTransition(previous, current)) {
			is QuickBuildTransition.FailureReported -> {
				// [QuickBuildTransition.FailureReported.isRepeat] is deliberately ignored: it
				// compares against `previous`, and a build always sits between a failure and the
				// next one, so it can never see the repeat this surface cares about.
				failureFlash(transition.failure)
			}

			is QuickBuildTransition.Settled -> {
				recoveryFlash(transition.status)
			}

			// A torn-down session must not flash a recovery later: the failure went away with
			// the session, which the user did not fix and does not need told about. A failed
			// START already flashes through the manager's message channel, so it raises
			// nothing here either.
			QuickBuildTransition.SessionStopped,
			QuickBuildTransition.StartFailed,
			-> {
				flashedFailure = null
				null
			}

			// In-flight and stale states say their piece on the status line and the icon. A bar
			// per transition would fire mid-typing for something the user already triggered.
			QuickBuildTransition.None,
			is QuickBuildTransition.ProvisioningStarted,
			is QuickBuildTransition.Compiling,
			is QuickBuildTransition.FullBuildNeeded,
			is QuickBuildTransition.DaemonStopped,
			-> {
				null
			}
		}

	/**
	 * The flash for reaching [QuickBuildStatus.Failed].
	 *
	 * @param failure what went wrong.
	 * @return the failure flash, or null when this failure is not new.
	 */
	private fun failureFlash(failure: SessionFailure): QuickBuildFlash? {
		// Compile errors only: a crash already flashes via the RELOAD_CRASHED notice, and a deploy
		// error reaching no surface at all is a separate open defect.
		if (failure !is SessionFailure.CompileError) {
			return null
		}
		// The same failure again is the user saving a file they have not fixed yet, or the
		// derived status settling. Either way they have seen this bar: a broken file that
		// re-flashes on every save is worse than not flashing at all.
		if (flashedFailure == failure) {
			return null
		}
		flashedFailure = failure
		return QuickBuildFlash.Failure(R.string.quick_build_flash_failed)
	}

	/**
	 * The flash for reaching [QuickBuildStatus.UpToDate], which is both "a build landed" and the
	 * session's resting state.
	 *
	 * @param current the up-to-date status now.
	 * @return the recovery flash, or null when nothing was outstanding or nothing actually built.
	 */
	private fun recoveryFlash(current: QuickBuildStatus.UpToDate): QuickBuildFlash? {
		if (flashedFailure == null) {
			return null
		}
		// A duration means a build genuinely landed. Arriving here without one is the session
		// settling (a warm compile, a restored session), which proves no fix.
		if (current.buildDurationMillis == null) {
			return null
		}
		flashedFailure = null
		return QuickBuildFlash.Recovery(R.string.quick_build_flash_recovered)
	}
}
