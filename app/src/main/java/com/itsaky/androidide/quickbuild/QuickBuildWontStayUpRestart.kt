package com.itsaky.androidide.quickbuild

/**
 * The Restart action of the "proxy app will not stay up" notice, decided at the tap rather than at
 * the show.
 *
 * A restart taken while the user's own Gradle build holds the one build slot tears the warm session
 * down and is then refused as slot-busy, so the action has to be gated on that build. Reading the
 * gate once, when the dialog is shown, is what this replaces: the dialog outlives the reading, so a
 * notice raised during a standard build kept a dead button for the whole build - with nothing on
 * screen saying why, since a dialog button carries no content description the way the toolbar's
 * does - and a notice raised before one kept a live button that would strand the session. Both
 * halves are the same one-shot read.
 *
 * A blocked tap therefore says why and leaves the notice up, rather than the notice promising a
 * remedy it will not perform. The user taps again when the build ends; nothing has to raise the
 * notice a second time.
 *
 * @property isBlockedByStandardBuild the live gate, read on every tap - see
 *   [com.itsaky.androidide.actions.build.QuickBuildAction.isBlockedByStandardBuild].
 * @property explainBlocked names the standard build on screen; the tap's only outcome while
 *   blocked.
 * @property restartAndReprovision rebuilds and reinstalls the proxy app, which is what the notice's
 *   copy promises.
 * @property dismiss closes the notice; only on a tap that acts.
 */
internal class QuickBuildWontStayUpRestart(
	private val isBlockedByStandardBuild: () -> Boolean,
	private val explainBlocked: () -> Unit,
	private val restartAndReprovision: () -> Unit,
	private val dismiss: () -> Unit,
) {
	/** Runs the tap: the restart, or the reason it cannot run yet. */
	fun onTapped() {
		if (isBlockedByStandardBuild()) {
			explainBlocked()
			return
		}
		dismiss()
		restartAndReprovision()
	}
}
