package com.itsaky.androidide.quickbuild

import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure

/**
 * What a Quick Build status change means, decided once for every surface that narrates one.
 *
 * The three presentation mappers - the Build Output log ([quickBuildOutputLines]), the bottom status
 * bar ([quickBuildStatusBarUpdate]) and the flashbar ([QuickBuildFlashes]) - word a change very
 * differently but classify it identically, so deciding it once means a new [QuickBuildStatus] is
 * handled in one exhaustive `when` instead of three that can drift apart.
 *
 * Not decided here: the copy, and the per-surface judgement of what counts as news - most of all
 * [QuickBuildStatus.UpToDate], which [Settled] hands back untouched for each surface to judge.
 */
internal sealed interface QuickBuildTransition {
	/** Nothing changed, so no surface has anything to say. */
	data object None : QuickBuildTransition

	/** The session went away. */
	data object SessionStopped : QuickBuildTransition

	/**
	 * A session start failed and nothing is running; the bolt keeps the error tone until the
	 * user's next tap or save. The Gradle cause is already narrated separately
	 * ([QuickBuildOutputNarrator.narrateProxyAppBuildFailure]) and flashed via the manager's
	 * message channel, so surfaces only owe the gesture that retries.
	 */
	data object StartFailed : QuickBuildTransition

	/**
	 * A full Gradle build started.
	 *
	 * @property kind which of the three it is, which is the whole reason this is not one state.
	 */
	data class ProvisioningStarted(
		val kind: ProvisioningKind,
	) : QuickBuildTransition

	/**
	 * A build of a save is running.
	 *
	 * @property runningGeneration the generation still live in the proxy app, one behind the build.
	 */
	data class Compiling(
		val runningGeneration: Long,
	) : QuickBuildTransition

	/**
	 * The session reached its resting state, which is both "a build just landed" and "nothing is
	 * happening".
	 *
	 * @property status the status whole, because each surface applies its own rule to it.
	 */
	data class Settled(
		val status: QuickBuildStatus.UpToDate,
	) : QuickBuildTransition

	/**
	 * A build did not land.
	 *
	 * @property failure what went wrong.
	 * @property isRepeat the previous status already carried this same failure, so this arrival is
	 *   the derived status settling rather than a new failure.
	 */
	data class FailureReported(
		val failure: SessionFailure,
		val isRepeat: Boolean,
	) : QuickBuildTransition

	/**
	 * The baseline is stale and only a full Gradle build moves it forward.
	 *
	 * @property reason what the live reload path could not absorb.
	 * @property awaitingRetry a rebaseline already ran and parked, so a surface must narrate a
	 *   failure the user resolves - matching the error tone the icon already shows - rather than
	 *   ordinary upcoming work.
	 */
	data class FullBuildNeeded(
		val reason: InvalidationReason,
		val awaitingRetry: Boolean,
	) : QuickBuildTransition

	/**
	 * The compile daemon died.
	 *
	 * @property restartFailed nothing is respawning it, so a surface must not claim a restart is
	 *   under way.
	 */
	data class DaemonStopped(
		val restartFailed: Boolean,
	) : QuickBuildTransition
}

/**
 * Which of the three full Gradle builds a [QuickBuildStatus.Provisioning] is. Calling a rebaseline
 * or a restart "the initial build" makes a failed one read as a broken session, so every surface
 * has to tell them apart.
 */
internal sealed interface ProvisioningKind {
	/**
	 * The baseline went stale and is being rebuilt.
	 *
	 * @property reason what invalidated it; carried because the log names it and the bar does not.
	 */
	data class Rebaseline(
		val reason: InvalidationReason,
	) : ProvisioningKind

	/** A session that was already live is being restarted. */
	data object Restart : ProvisioningKind

	/** A session's first provision. */
	data object Initial : ProvisioningKind
}

/**
 * Classifies a status change for every presentation surface.
 *
 * @param previous the status before this change; null on the first emission after subscribing.
 * @param current the status now.
 * @return what the change means, or [QuickBuildTransition.None] when nothing changed.
 */
internal fun quickBuildTransition(
	previous: QuickBuildStatus?,
	current: QuickBuildStatus,
): QuickBuildTransition {
	if (previous == current) {
		return QuickBuildTransition.None
	}
	return when (current) {
		is QuickBuildStatus.Hidden -> {
			if (current.lastStartFailed) {
				QuickBuildTransition.StartFailed
			} else {
				QuickBuildTransition.SessionStopped
			}
		}

		is QuickBuildStatus.Provisioning -> {
			QuickBuildTransition.ProvisioningStarted(provisioningKind(previous, current))
		}

		is QuickBuildStatus.Building -> {
			QuickBuildTransition.Compiling(current.runningGeneration)
		}

		is QuickBuildStatus.UpToDate -> {
			QuickBuildTransition.Settled(current)
		}

		is QuickBuildStatus.Failed -> {
			QuickBuildTransition.FailureReported(
				failure = current.failure,
				isRepeat = previous is QuickBuildStatus.Failed && previous.failure == current.failure,
			)
		}

		is QuickBuildStatus.NeedsFullBuild -> {
			QuickBuildTransition.FullBuildNeeded(current.reason, current.awaitingRetry)
		}

		is QuickBuildStatus.Reconnecting -> {
			QuickBuildTransition.DaemonStopped(current.restartFailed)
		}
	}
}

/**
 * Tells the three provisioning kinds apart.
 *
 * The status carries the rebaseline reason deliberately: the [QuickBuildStatus.NeedsFullBuild]
 * that precedes a rebaseline is a hop a surface is not guaranteed to see, since it reads a
 * conflating StateFlow and resubscribes from scratch on every activity recreation. A restart needs
 * no such carried flag - the reducer goes straight from the live state to provisioning in one
 * transition, so there is no hop to lose.
 *
 * @param previous the status before this change; null on the first emission after subscribing.
 * @param current the provisioning status now.
 * @return which build this is.
 */
private fun provisioningKind(
	previous: QuickBuildStatus?,
	current: QuickBuildStatus.Provisioning,
): ProvisioningKind =
	when {
		current.rebaselineReason != null -> {
			ProvisioningKind.Rebaseline(current.rebaselineReason!!)
		}

		previous.isLiveSession() -> {
			ProvisioningKind.Restart
		}

		else -> {
			ProvisioningKind.Initial
		}
	}

/**
 * Whether this status means a session was already running - the thing every narration surface
 * needs in order to tell a restart from a first build.
 *
 * @receiver the status to test; null (a first emission) is not a live session.
 * @return true for every status a provisioned session can be in, excluding
 *   [QuickBuildStatus.Provisioning], which is the state being entered rather than evidence of one.
 */
internal fun QuickBuildStatus?.isLiveSession(): Boolean =
	when (this) {
		null,
		is QuickBuildStatus.Hidden,
		is QuickBuildStatus.Provisioning,
		-> false

		is QuickBuildStatus.Building,
		is QuickBuildStatus.UpToDate,
		is QuickBuildStatus.Failed,
		is QuickBuildStatus.NeedsFullBuild,
		is QuickBuildStatus.Reconnecting,
		-> true
	}
