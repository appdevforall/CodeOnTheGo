package com.itsaky.androidide.activities.editor

/**
 * Whether switching build type has to ask the user first (ADFA-4128). Both Quick Build and
 * Standard Run install under the project's real applicationId, so whichever runs second
 * replaces the app the other installed.
 */
sealed interface QuickBuildClobberConfirmation {
	/** The slot holds nothing this build would overwrite. The only silent case. */
	data object NotNeeded : QuickBuildClobberConfirmation

	/** [applicationId]'s slot holds the other build type, which this install replaces. */
	data class Needed(
		val applicationId: String,
	) : QuickBuildClobberConfirmation

	/**
	 * The project's applicationId did not resolve, so what occupies the slot is unknowable.
	 * Confirm: an unknown occupant is exactly the case a silent install would destroy, and
	 * this is reachable in normal use - a project whose Gradle model has not published
	 * `mainArtifact` yet, or a variant switch in flight.
	 */
	data object NeededForUnknownAppId : QuickBuildClobberConfirmation
}

/**
 * Decides the confirmation for one build-type switch. Fails CLOSED: an unresolvable
 * [realApplicationId] confirms rather than installing, because "we cannot tell what is
 * installed" and "nothing is installed" are not the same answer.
 *
 * @param realApplicationId the project's own applicationId, or null when it did not resolve
 * @param needsConfirm asks whether the installed app is the other build type
 */
internal suspend fun quickBuildClobberConfirmation(
	realApplicationId: String?,
	needsConfirm: suspend (String) -> Boolean,
): QuickBuildClobberConfirmation =
	when {
		realApplicationId == null -> QuickBuildClobberConfirmation.NeededForUnknownAppId
		needsConfirm(realApplicationId) -> QuickBuildClobberConfirmation.Needed(realApplicationId)
		else -> QuickBuildClobberConfirmation.NotNeeded
	}

/**
 * What the install still has to ask, given what the Run tap already settled.
 *
 * The tap asks about the selection as of the tap - which is what the build then builds - so the
 * common case is that [now] repeats [atTap] and the user is not asked twice for one Run. What
 * this re-check catches is the answer CHANGING while the build ran: the APK names a different
 * package than the tap-time selection did, or something was installed or removed under that
 * package in the meantime.
 *
 * @param atTap the confirmation the tap settled, or null when no tap answered for this build
 *   (an activity that never ran the tap check, a build started by something other than the
 *   button) - which asks again rather than assuming consent nobody gave.
 * @param now the confirmation the APK being installed calls for, re-checked against the live
 *   package state.
 * @return [QuickBuildClobberConfirmation.NotNeeded] when the tap already answered this - the
 *   same confirmation, an unknown-id confirmation the install has since resolved, or a slot the
 *   tap found empty that the install could not re-check - otherwise [now].
 */
internal fun installTimeClobberConfirmation(
	atTap: QuickBuildClobberConfirmation?,
	now: QuickBuildClobberConfirmation,
): QuickBuildClobberConfirmation =
	when {
		now == atTap -> QuickBuildClobberConfirmation.NotNeeded

		// The tap could not name the package and asked anyway: "whatever is installed under
		// this app's id, this replaces it", and the user said yes. The install resolving the id
		// and finding the other build type there is that answer with the name filled in, not a
		// second question - so one Run does not cost two dialogs.
		atTap == QuickBuildClobberConfirmation.NeededForUnknownAppId &&
			now is QuickBuildClobberConfirmation.Needed -> QuickBuildClobberConfirmation.NotNeeded

		// The tap resolved the id and found nothing to replace; an unknown id here means our own
		// APK did not parse, not that an occupant appeared. Asking would turn an ordinary Run
		// into a "replace the installed app?" dialog whose decline drops the install silently.
		atTap == QuickBuildClobberConfirmation.NotNeeded &&
			now == QuickBuildClobberConfirmation.NeededForUnknownAppId -> QuickBuildClobberConfirmation.NotNeeded

		else -> now
	}
