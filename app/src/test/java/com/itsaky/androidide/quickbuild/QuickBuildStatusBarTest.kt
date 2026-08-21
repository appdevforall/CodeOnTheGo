package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.session.SessionReducer
import org.junit.Test

/**
 * What the bottom status bar shows across a Quick Build session.
 *
 * Two behaviours are pinned hardest: a failure must say BUILD FAILED on the bar, and a later
 * successful build must overwrite it, so the bar can never sit on BUILD FAILED over a green
 * build.
 */
class QuickBuildStatusBarTest {
	private fun update(
		previous: QuickBuildStatus?,
		current: QuickBuildStatus,
	) = quickBuildStatusBarUpdate(previous, current)

	private fun compileError() =
		QuickBuildStatus.Failed(
			4L,
			SessionFailure.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "boom", "/p/Foo.kt", 12, 5)),
			),
		)

	@Test
	fun `a failure says BUILD FAILED`() {
		val shown = update(QuickBuildStatus.Building(4L), compileError())
		assertThat(shown).isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_failed))
	}

	@Test
	fun `a deploy failure does not claim the build failed`() {
		// The build succeeded; only the delivery failed, which is what the Build Output pane
		// narrates. BUILD FAILED on the bar would contradict the pane.
		val shown =
			update(
				QuickBuildStatus.Building(4L),
				QuickBuildStatus.Failed(4L, SessionFailure.DeployError("proxy app is not running")),
			)
		assertThat(shown)
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_deploy_failed))
	}

	@Test
	fun `the same deploy failure settling does not rewrite the bar`() {
		val failed = QuickBuildStatus.Failed(4L, SessionFailure.DeployError("gone"))
		assertThat(update(failed, failed)).isNull()
	}

	@Test
	fun `a landed build overwrites a failure`() {
		// The reported bug: fix the error, build green, bar still reads BUILD FAILED.
		val shown =
			update(
				compileError(),
				QuickBuildStatus.UpToDate(generation = 5L, buildDurationMillis = 1970L),
			)
		assertThat(shown)
			.isEqualTo(
				QuickBuildStatusBarUpdate.Show(
					R.string.quick_build_status_reloaded,
					// The pane reports the same loop as "2.0s"; a bare 1970 beside it reads as a
					// second, different measurement.
					listOf("2.0s"),
				),
			)
	}

	@Test
	fun `a restart deploy is phrased as a restart`() {
		val shown =
			update(
				QuickBuildStatus.Building(4L),
				QuickBuildStatus.UpToDate(5L, buildDurationMillis = 2500L, restarted = true),
			)
		assertThat(shown)
			.isEqualTo(
				QuickBuildStatusBarUpdate.Show(
					R.string.quick_build_status_restarted,
					listOf("2.5s"),
				),
			)
	}

	@Test
	fun `compiling shows while a build runs`() {
		val shown = update(QuickBuildStatus.UpToDate(4L, null), QuickBuildStatus.Building(4L))
		assertThat(shown)
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_compiling))
	}

	@Test
	fun `an unchanged status leaves the bar alone`() {
		val status = QuickBuildStatus.Building(3L)
		assertThat(update(status, status)).isNull()
	}

	@Test
	fun `the same failure settling does not rewrite the bar`() {
		assertThat(update(compileError(), compileError())).isNull()
	}

	@Test
	fun `settling to the resting state keeps the reloaded line visible`() {
		val landed = QuickBuildStatus.UpToDate(5L, buildDurationMillis = 1970L)
		val settled = QuickBuildStatus.UpToDate(5L, buildDurationMillis = null)
		assertThat(update(landed, settled)).isNull()
	}

	@Test
	fun `first emission of transient states still renders after an activity recreation`() {
		// The bar shows state, not history - a session mid-provision or mid-failure must
		// read correctly when the collector resubscribes.
		assertThat(update(null, QuickBuildStatus.Provisioning()))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_provisioning))
		assertThat(update(null, compileError()))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_failed))
	}

	@Test
	fun `a rebaseline says rebuilding, not the initial build`() {
		// Driven from the reducer, so this is the status the bar is really handed. Pairing a
		// hand-written NeedsFullBuild with Provisioning - what this test used to do - passes
		// against an inference that fails on the device: the bar collects a conflating StateFlow
		// on the main thread, so the NeedsFullBuild hop is routinely never delivered, and a
		// recreated activity resubscribes mid-rebaseline with no previous status at all. Both
		// of those cases would otherwise read "running the initial full build".
		val invalidated = QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 4L)
		val started = SessionReducer().reduce(invalidated, SessionEvent.ProxyAppRebuildStarted).state
		val rebaselining = QuickBuildStatus.from(started)

		assertThat(update(QuickBuildStatus.UpToDate(4L, buildDurationMillis = null), rebaselining))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_rebuilding))
		assertThat(update(null, rebaselining))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_rebuilding))
	}

	@Test
	fun `a session's first build still says provisioning`() {
		assertThat(update(QuickBuildStatus.Hidden(), QuickBuildStatus.Provisioning()))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_provisioning))
	}

	@Test
	fun `a restarted session says restarting, not the initial build`() {
		// T15: the bar is one of the two surfaces that can tell the user the restart they asked
		// for is underway. Saying "running initial full build" on an hour-old session is the same
		// mislabel the rebaseline case above fixed.
		val live = QuickBuildStatus.UpToDate(4L, buildDurationMillis = null)

		assertThat(update(live, QuickBuildStatus.Provisioning()))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_restarting))
	}

	@Test
	fun `a restart from a failed session also says restarting`() {
		// Where the escape hatch is actually reached from, and the case that must overwrite
		// BUILD FAILED rather than leave it standing over a running restart.
		assertThat(update(compileError(), QuickBuildStatus.Provisioning()))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_restarting))
	}

	@Test
	fun `first emission of resting states says nothing`() {
		assertThat(update(null, QuickBuildStatus.UpToDate(4L, null))).isNull()
	}

	@Test
	fun `a cancelled build does not leave compiling stuck`() {
		val shown = update(QuickBuildStatus.Building(4L), QuickBuildStatus.UpToDate(4L, null))
		assertThat(shown)
			.isEqualTo(
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_ready, onlyIfOwned = true),
			)
	}

	@Test
	fun `leaving a failure without a build defers to whoever owns the bar`() {
		// A standard build's baseline refresh moves the session Failed -> UpToDate with no
		// landed Quick Build. That build's own result line is on the bar and must stay until
		// the next build starts, so the "ready" refresh only applies if Quick Build still
		// owns the line.
		val shown = update(compileError(), QuickBuildStatus.UpToDate(4L, null))
		assertThat(shown)
			.isEqualTo(
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_ready, onlyIfOwned = true),
			)
	}

	@Test
	fun `a failure line is a takeover so it persists until the next build`() {
		// A failure stays on the bar until the next build takes the line over, so the Show
		// must NOT be gated on ownership.
		val shown = update(QuickBuildStatus.Building(4L), compileError()) as QuickBuildStatusBarUpdate.Show
		assertThat(shown.onlyIfOwned).isFalse()
	}

	@Test
	fun `session end clears the bar`() {
		assertThat(update(QuickBuildStatus.UpToDate(4L, null), QuickBuildStatus.Hidden()))
			.isEqualTo(QuickBuildStatusBarUpdate.Clear)
	}

	@Test
	fun `a failed start shows the retry line and the save-clear removes it`() {
		// The flash fades and Build Output may be collapsed; the bar keeps the one line that
		// explains the error-toned bolt (Q8).
		assertThat(update(QuickBuildStatus.Provisioning(), QuickBuildStatus.Hidden(lastStartFailed = true)))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_start_failed))
		// The save that clears the tone clears the bar with it.
		assertThat(update(QuickBuildStatus.Hidden(lastStartFailed = true), QuickBuildStatus.Hidden()))
			.isEqualTo(QuickBuildStatusBarUpdate.Clear)
	}

	@Test
	fun `a failed start still shows after an activity recreation`() {
		// The bar shows state, not history: a recreation resubscribes with previous == null
		// and the failed start must still read correctly.
		assertThat(update(null, QuickBuildStatus.Hidden(lastStartFailed = true)))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_start_failed))
	}

	@Test
	fun `an invalidation names the full-build ask`() {
		val shown =
			update(
				QuickBuildStatus.UpToDate(4L, null),
				QuickBuildStatus.NeedsFullBuild(InvalidationReason.MANIFEST_CHANGED, 4L),
			)
		assertThat(shown)
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_needs_full_build))
	}

	@Test
	fun `a parked rebaseline reads as the failure it is, not upcoming work`() {
		// The icon shows the error bolt for awaitingRetry; a bar still narrating ordinary
		// upcoming work next to it contradicts the icon. A save with a fix retries by itself,
		// so that is the gesture to name.
		val shown =
			update(
				QuickBuildStatus.Provisioning(InvalidationReason.MANIFEST_CHANGED),
				QuickBuildStatus.NeedsFullBuild(
					InvalidationReason.MANIFEST_CHANGED,
					4L,
					awaitingRetry = true,
				),
			)
		assertThat(shown)
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_rebuild_failed))
	}

	@Test
	fun `a daemon respawn is narrated and ready replaces it`() {
		assertThat(update(QuickBuildStatus.UpToDate(4L, null), QuickBuildStatus.Reconnecting(4L)))
			.isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_reconnecting))
		assertThat(update(QuickBuildStatus.Reconnecting(4L), QuickBuildStatus.UpToDate(4L, null)))
			.isEqualTo(
				QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_ready, onlyIfOwned = true),
			)
	}

	@Test
	fun `a respawn that failed stops the bar claiming a restart is under way`() {
		// The bar said "compile daemon restarting" for as long as the session stayed degraded,
		// including after the respawn failed and nothing was restarting it - while the snackbar
		// three lines away said the restart had failed and asked for a tap.
		assertThat(
			update(
				QuickBuildStatus.Reconnecting(4L),
				QuickBuildStatus.Reconnecting(4L, restartFailed = true),
			),
		).isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_compiler_down))
	}

	@Test
	fun `a tap that retries the respawn puts the restarting line back`() {
		assertThat(
			update(
				QuickBuildStatus.Reconnecting(4L, restartFailed = true),
				QuickBuildStatus.Reconnecting(4L),
			),
		).isEqualTo(QuickBuildStatusBarUpdate.Show(R.string.quick_build_status_reconnecting))
	}
}
