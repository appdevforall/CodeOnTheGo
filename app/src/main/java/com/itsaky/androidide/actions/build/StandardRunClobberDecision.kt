package com.itsaky.androidide.actions.build

/**
 * Whether a Run has to ask the user before it may replace whatever currently holds the project's
 * applicationId (ADFA-4128).
 */
internal sealed interface StandardRunClobberDecision {
	/**
	 * Build without asking. A plugin project builds a `.cgp`, not an APK, so nothing it produces
	 * can occupy the project's applicationId - the confirm would be asking about a package this
	 * build never installs.
	 */
	object SkipConfirm : StandardRunClobberDecision

	/**
	 * Ask before building, because this Run installs under the project's real applicationId and so
	 * replaces a Quick Build proxy app sitting there.
	 *
	 * @param applicationId the id the confirm names, or `null` when it did not resolve - the
	 *   confirm is still raised, in its unknown-occupant wording.
	 */
	data class AskFirst(
		val applicationId: String?,
	) : StandardRunClobberDecision
}

/**
 * Decides whether the Run at hand needs the clobber confirm.
 *
 * Only [isPluginProject] may skip it: a missing or blank [applicationId] is not a reason to
 * proceed silently, because an unnameable occupant is still an occupant.
 */
internal fun standardRunClobberDecision(
	isPluginProject: Boolean,
	applicationId: String?,
): StandardRunClobberDecision =
	if (isPluginProject) {
		StandardRunClobberDecision.SkipConfirm
	} else {
		StandardRunClobberDecision.AskFirst(applicationId?.takeIf { it.isNotBlank() })
	}
