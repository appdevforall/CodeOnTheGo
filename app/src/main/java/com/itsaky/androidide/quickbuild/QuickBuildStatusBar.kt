package com.itsaky.androidide.quickbuild

import androidx.annotation.StringRes
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure

/**
 * What the editor's one-line bottom status bar should do for a Quick Build status change.
 *
 * The bar is the same surface a standard Gradle build narrates task-by-task, so Quick Build uses it
 * the same way: compiling, landed, BUILD FAILED. Resource ids rather than strings so the mapping
 * stays a pure JVM function (testable without a Context) while the surface stays translatable -
 * unlike [quickBuildOutputLines], whose Build Output copy is deliberately untranslated log text.
 */
sealed interface QuickBuildStatusBarUpdate {
	/**
	 * Replace the bar's text.
	 *
	 * @property text the string resource to show.
	 * @property args positional format arguments for [text], in order.
	 * @property onlyIfOwned apply only if Quick Build's text is still on the bar, so a passive
	 *   refresh cannot clobber a line another writer took over.
	 */
	data class Show(
		@StringRes val text: Int,
		val args: List<Any> = emptyList(),
		val onlyIfOwned: Boolean = false,
	) : QuickBuildStatusBarUpdate

	/** Clear the bar - but only if the last write was Quick Build's (the caller tracks that). */
	data object Clear : QuickBuildStatusBarUpdate
}

/**
 * Maps a status change to a status-bar update, or null to leave the bar untouched.
 *
 * Unlike [quickBuildOutputLines] this does not suppress the first emission wholesale: the bar shows
 * state, not history, so an in-progress or failed session must still read correctly after an
 * activity recreation. Only the resting states stay silent on first emission, so a "Project
 * initialized" message is not stomped by a session that has nothing to say.
 *
 * @param previous the status before this change; null on the first emission after subscribing.
 * @param current the status now.
 * @return the update to apply, or null for no change.
 */
fun quickBuildStatusBarUpdate(
	previous: QuickBuildStatus?,
	current: QuickBuildStatus,
): QuickBuildStatusBarUpdate? {
	return when (val transition = quickBuildTransition(previous, current)) {
		QuickBuildTransition.None -> {
			null
		}

		QuickBuildTransition.SessionStopped -> {
			QuickBuildStatusBarUpdate.Clear
		}

		QuickBuildTransition.StartFailed -> {
			// The flash fades and Build Output may be collapsed, so the bar keeps the one line
			// that explains the error-toned bolt and names the gesture that retries. Mirrors
			// the parked-rebaseline text; a save also clears this (via SessionStopped -> Clear).
			QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_start_failed)
		}

		is QuickBuildTransition.ProvisioningStarted -> {
			when (transition.kind) {
				is ProvisioningKind.Rebaseline -> {
					// The bar has no room for the reason; Build Output names it.
					QuickBuildStatusBarUpdate.Show(R.string.quick_build_rebuilding)
				}

				ProvisioningKind.Restart -> {
					QuickBuildStatusBarUpdate.Show(R.string.quick_build_restarting)
				}

				ProvisioningKind.Initial -> {
					QuickBuildStatusBarUpdate.Show(R.string.quick_build_provisioning)
				}
			}
		}

		is QuickBuildTransition.Compiling -> {
			QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_compiling)
		}

		is QuickBuildTransition.Settled -> {
			upToDateUpdate(previous, transition.status)
		}

		is QuickBuildTransition.FailureReported -> {
			if (transition.isRepeat) {
				null
			} else if (transition.failure is SessionFailure.DeployError) {
				// The build succeeded and only the delivery failed, which is what the Build
				// Output pane says; BUILD FAILED here sends the reader looking for a compile
				// error that does not exist.
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_deploy_failed)
			} else {
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_failed)
			}
		}

		is QuickBuildTransition.FullBuildNeeded -> {
			// Parked after a failed rebaseline the icon already colors as an error - the bar
			// must not narrate ordinary upcoming work next to it. A save with a fix retries by
			// itself, so that is the gesture to name.
			if (transition.awaitingRetry) {
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_rebuild_failed)
			} else {
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_needs_full_build)
			}
		}

		is QuickBuildTransition.DaemonStopped -> {
			// After a failed respawn nothing is restarting it, so the "restarting" line asserts
			// work that is not happening - and it contradicts the snackbar that just said the
			// restart failed and asked for a tap.
			if (transition.restartFailed) {
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_compiler_down)
			} else {
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_reconnecting)
			}
		}
	}
}

/**
 * The update for reaching [QuickBuildStatus.UpToDate], which is both "a build just landed" and
 * the session's resting state.
 *
 * @param previous the status before this change.
 * @param current the up-to-date status now.
 * @return the update, or null when arriving here is not news (settling, or first emission).
 */
private fun upToDateUpdate(
	previous: QuickBuildStatus?,
	current: QuickBuildStatus.UpToDate,
): QuickBuildStatusBarUpdate? =
	when {
		// A duration means a build landed - the moment BUILD FAILED must be overwritten.
		current.buildDurationMillis != null -> {
			val text =
				if (current.restarted) {
					R.string.quick_build_status_restarted
				} else {
					R.string.quick_build_status_reloaded
				}
			// Generations are internal bookkeeping - the bar shows only the duration, in the
			// same seconds format the Build Output pane uses, since it is the same loop.
			// !! is safe: this branch is guarded by buildDurationMillis != null above.
			QuickBuildStatusBarUpdate.Show(
				text,
				listOf(seconds(current.buildDurationMillis!!)),
			)
		}

		// First emission of the resting state: nothing landed, say nothing.
		previous == null -> {
			null
		}

		// Settling after a landed build: keep the reloaded line visible.
		previous is QuickBuildStatus.UpToDate -> {
			null
		}

		// Out of any transient state (a cancelled build, a respawned daemon, a cleared
		// failure) with nothing deployed: Quick Build's own transient text must not linger,
		// but this is a passive refresh, not a build landing - if a standard build's task or
		// result line has taken the bar meanwhile (the external-build baseline refresh lands
		// exactly here), that line stays until the next build starts.
		else -> {
			QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_ready, onlyIfOwned = true)
		}
	}
