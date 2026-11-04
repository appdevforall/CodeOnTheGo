package com.itsaky.androidide.quickbuild

import com.itsaky.androidide.plugins.services.QuickBuildResult
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure

/**
 * Reads one Quick Build tap's outcome off the session's [QuickBuildStatus] stream, for a plugin
 * that started the tap (ADFA-6493).
 *
 * The session reports no per-tap result, only a conflated status, so this infers one: a change
 * away from [start] is this tap's doing, since only one tap runs at a time. Conflation can hide
 * the in-between Building, which is why a landing is recognised by what it carries (a new
 * generation, a build time) rather than by having seen the build start.
 *
 * Feed it from one coroutine; only [onCancelRequested] may be called from another thread.
 *
 * @param start the status when the plugin asked, before the tap saves: a build the save itself
 *   starts carries the plugin's changes, so it is this tap's result.
 * @param startedAtMillis when the plugin asked, for [QuickBuildResult.Reloaded.durationMillis].
 * @param buildOutput the Build Output pane's text, attached to a failure.
 */
internal class QuickBuildRunTracker(
	private val start: QuickBuildStatus,
	private val startedAtMillis: Long,
	private val buildOutput: () -> String?,
) {
	/** A proxy app build ran, so the landing relaunched the app. */
	private var provisioned = false

	/** The generation a live build was replacing; landing back on it with no build time is a stop. */
	private var buildingFrom: Long? = null

	/** A build or provision was seen, so a later idle status ends the run rather than waiting. */
	private var sawWork = false

	/** The status has left [start] at least once, so a status equal to it again is news. */
	private var moved = false

	@Volatile
	private var cancelRequested = false

	/**
	 * Whether nothing is in flight that will move the status, so the caller should give the tap
	 * [QUIET_MILLIS] to start something before calling [onQuiet]. A tap with nothing to build
	 * moves nothing at all.
	 */
	var awaitingQuiet = true
		private set

	/** A stop was asked for this run, so a landing back where it began reads as cancelled. */
	fun onCancelRequested() {
		cancelRequested = true
	}

	/** The result [status] settles, or null while the run is still going. */
	fun onStatus(
		status: QuickBuildStatus,
		nowMillis: Long,
	): QuickBuildResult? {
		awaitingQuiet = false
		if (status != start) moved = true
		when (status) {
			is QuickBuildStatus.Provisioning -> {
				provisioned = true
				buildingFrom = null
				sawWork = true
				return null
			}

			is QuickBuildStatus.Building -> {
				buildingFrom = status.runningGeneration
				sawWork = true
				return null
			}

			is QuickBuildStatus.NeedsFullBuild -> {
				if (status.awaitingRetry) {
					// A park ends this tap's rebuild, even though a later return to Code On The Go
					// may retry it: that retry is not this tap, and waiting on it could never end.
					// A stopped rebaseline parks here too, and is no sign of broken build files.
					if (moved) return if (cancelRequested) QuickBuildResult.Cancelled else parked(status)
				} else {
					// A rebaseline is in flight; it ends in Provisioning.
					sawWork = true
					return null
				}
			}

			is QuickBuildStatus.Reconnecting -> {
				// The respawn re-runs a build the daemon took down with it and lands with no
				// build time, which would otherwise read as that build being stopped.
				buildingFrom = null
				if (!status.restartFailed) {
					// The respawn lands in an idle status and may then build; wait for it.
					return null
				}
				if (moved) return failed(COMPILER_DOWN)
				// Unmoved, a retried respawn may have failed again too fast to show; the quiet
				// window reports it.
			}

			is QuickBuildStatus.Failed -> {
				if (moved) return status.failure.toResult()
			}

			is QuickBuildStatus.UpToDate -> {
				// A stopped build lands back on the generation it was replacing, with no build time.
				if (buildingFrom != null && status.generation == buildingFrom && status.buildDurationMillis == null) {
					return QuickBuildResult.Cancelled
				}
				val startGeneration = start.generationOrNull()
				val landed = provisioned || status.generation != startGeneration || (status.buildDurationMillis != null && moved)
				if (landed) {
					// No generation at the start means no session: this landing is a first install.
					val restarted = provisioned || startGeneration == null || status.restarted
					return QuickBuildResult.Reloaded(nowMillis - startedAtMillis, restarted)
				}
			}

			is QuickBuildStatus.Hidden -> {
				if (status.lastStartFailed && moved) {
					return failed("Quick Build could not start.")
				}
				// A stop during the first build drops the session.
				if (sawWork) return QuickBuildResult.Cancelled
			}
		}
		awaitingQuiet = true
		return null
	}

	/**
	 * Whether the quiet window after [current] should go on waiting while the session still owes
	 * the tap an answer. A tap queued behind the background warm compile reads as [start] until
	 * its build starts. Only a live status always answers or withdraws the ask; a compiler that
	 * could not restart keeps it recorded indefinitely.
	 */
	fun waitsOnAsk(current: QuickBuildStatus): Boolean =
		!cancelRequested && (current is QuickBuildStatus.UpToDate || current is QuickBuildStatus.Failed)

	/** The result when [QUIET_MILLIS] passed after [current] with nothing starting. */
	fun onQuiet(current: QuickBuildStatus): QuickBuildResult =
		when {
			cancelRequested -> QuickBuildResult.Cancelled

			// Nothing to build: the app still runs the last build, which failed.
			current is QuickBuildStatus.Failed -> current.failure.toResult()

			current is QuickBuildStatus.Hidden -> QuickBuildResult.Refused("Quick Build did not start.")

			// Still parked or still down: the tap retried, and the conflated status hid the attempt.
			current is QuickBuildStatus.NeedsFullBuild && current.awaitingRetry -> parked(current)

			current is QuickBuildStatus.Reconnecting && current.restartFailed -> failed(COMPILER_DOWN)

			else -> QuickBuildResult.UpToDate
		}

	private fun QuickBuildStatus.generationOrNull(): Long? =
		when (this) {
			is QuickBuildStatus.UpToDate -> generation
			is QuickBuildStatus.Building -> runningGeneration
			is QuickBuildStatus.Failed -> runningGeneration
			is QuickBuildStatus.NeedsFullBuild -> runningGeneration
			is QuickBuildStatus.Reconnecting -> runningGeneration
			is QuickBuildStatus.Hidden, is QuickBuildStatus.Provisioning -> null
		}

	/**
	 * The result when the run did not settle within [ceilingMillis]; the session may still be busy.
	 * A stopped rebaseline can stay in Provisioning, and its accepted stop still reads as cancelled.
	 */
	fun onGaveUp(ceilingMillis: Long): QuickBuildResult =
		if (cancelRequested) {
			QuickBuildResult.Cancelled
		} else {
			failed("Quick Build did not finish within ${ceilingMillis / 60_000} minutes; this run was given up on.")
		}

	private fun SessionFailure.toResult(): QuickBuildResult =
		when (this) {
			is SessionFailure.CompileError -> failed(compileErrorSummary(diagnostics))
			is SessionFailure.DeployError -> if (appNotRunning) QuickBuildResult.AppNotRunning(message) else failed(message)
			is SessionFailure.ProxyAppCrash -> failed("The app crashed after the reload: $summary")
		}

	private fun failed(reason: String) = QuickBuildResult.Failed(reason, buildOutput())

	private fun parked(status: QuickBuildStatus.NeedsFullBuild): QuickBuildResult =
		if (status.reason == InvalidationReason.INSTALL_NOT_CONFIRMED) {
			failed("The app was rebuilt, but its install was not confirmed. Start Quick Build again to retry the install.")
		} else {
			failed("The full build failed. Fix the build files and start Quick Build again.")
		}

	companion object {
		/** How long a tap may take to start a build before it counts as having had nothing to build. */
		const val QUIET_MILLIS = 5_000L

		/** How often a quiet window held open by an outstanding ask checks it again. */
		const val ASK_POLL_MILLIS = 250L

		private const val COMPILER_DOWN =
			"The Quick Build compiler stopped and could not restart. Start Quick Build again to retry."

		/** How many compile errors a failure names; the rest are in the build output. */
		private const val MAX_ERRORS = 5

		internal fun compileErrorSummary(diagnostics: List<BuildDiagnostic>): String {
			val errors = diagnostics.filter { it.severity == BuildDiagnostic.Severity.ERROR }.ifEmpty { diagnostics }
			if (errors.isEmpty()) return "The change did not compile."
			val lines =
				errors.take(MAX_ERRORS).map { d ->
					// Compilers report an unknown position as 0 or -1; a column means nothing without its line.
					val line = d.line?.takeIf { it > 0 }
					val column = d.column?.takeIf { it > 0 && line != null }
					listOfNotNull(d.file?.let { f -> listOfNotNull(f, line, column).joinToString(":") }, d.message)
						.joinToString(": ")
				}
			val more = if (errors.size > MAX_ERRORS) "\n(${errors.size - MAX_ERRORS} more)" else ""
			return "The change did not compile:\n" + lines.joinToString("\n") + more
		}
	}
}
