package org.appdevforall.cotg.quickbuild.domain.session

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.junit.jupiter.api.Test

class QuickBuildStatusTest {
	@Test
	fun `idle maps to hidden`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Idle()))
			.isEqualTo(QuickBuildStatus.Hidden())
	}

	@Test
	fun `provisioning maps to provisioning`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Provisioning()))
			.isEqualTo(QuickBuildStatus.Provisioning())
	}

	@Test
	fun `who asked for a provision does not change what the surface shows`() {
		// userInitiated exists to decide where the user ENDS UP, not what the status line and
		// the toolbar icon say. Leaking it into the derived status would also break the
		// StateFlow conflation the toolbar repaint depends on.
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Provisioning(userInitiated = true)))
			.isEqualTo(QuickBuildStatus.from(QuickBuildSessionState.Provisioning(userInitiated = false)))
	}

	@Test
	fun `background prebuilding maps to hidden - the user never asked for it`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prebuilding(tapQueued = false)))
			.isEqualTo(QuickBuildStatus.Hidden())
	}

	@Test
	fun `prebuilding with a queued tap maps to provisioning`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prebuilding(tapQueued = true)))
			.isEqualTo(QuickBuildStatus.Provisioning())
	}

	@Test
	fun `ready with no failure maps to up to date`() {
		val state = QuickBuildSessionState.Ready(3)

		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(QuickBuildStatus.UpToDate(3, buildDurationMillis = null))
	}

	// An error state must never map to Building, or the banner sticks on "Compiling...".
	@Test
	fun `ready with a failure maps to failed`() {
		val failure =
			SessionFailure.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "msg", "A.kt", 1, 1)),
			)
		val state = QuickBuildSessionState.Ready(3, lastFailure = failure)

		assertThat(QuickBuildStatus.from(state)).isEqualTo(QuickBuildStatus.Failed(3, failure))
	}

	@Test
	fun `building maps to building`() {
		val state = QuickBuildSessionState.Building(3)

		assertThat(QuickBuildStatus.from(state)).isEqualTo(QuickBuildStatus.Building(3))
	}

	// The background warm compile deploys nothing and the proxy
	// app is genuinely current - it must not present as a blocking Building for its
	// whole 12-50s window.
	@Test
	fun `a warm-compiling build maps to up to date, not building`() {
		val state = QuickBuildSessionState.Building(3, warmingCompiler = true)

		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(QuickBuildStatus.UpToDate(3, buildDurationMillis = null))
	}

	// A crash of the running generation observed
	// mid-warm-compile surfaces immediately, exactly as it would outside the warm-compile window.
	@Test
	fun `a warm-compiling build with a pending crash maps to failed`() {
		val crash = SessionFailure.ProxyAppCrash("NPE in onCreate")
		val state = QuickBuildSessionState.Building(3, warmingCompiler = true, pendingCrash = crash)

		assertThat(QuickBuildStatus.from(state)).isEqualTo(QuickBuildStatus.Failed(3, crash))
	}

	@Test
	fun `deployed maps to up to date with the build duration`() {
		val state = QuickBuildSessionState.Deployed(4, 900)

		assertThat(QuickBuildStatus.from(state)).isEqualTo(QuickBuildStatus.UpToDate(4, 900))
	}

	@Test
	fun `restarted deploy maps to up to date with the restart flag - distinct surface`() {
		val state = QuickBuildSessionState.Deployed(4, 900, restarted = true)

		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(QuickBuildStatus.UpToDate(4, 900, restarted = true))
	}

	@Test
	fun `invalidated maps to needs full build`() {
		val state = QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 3)

		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(QuickBuildStatus.NeedsFullBuild(InvalidationReason.MANIFEST_CHANGED, 3))
	}

	@Test
	fun `an invalidated session awaiting retry carries that into the status`() {
		// Without this the surface shows the ordinary "next build is full" bolt while a failed
		// rebaseline sits parked waiting for the user to fix it by hand.
		val state =
			QuickBuildSessionState.Invalidated(
				InvalidationReason.GRADLE_CONFIG_CHANGED,
				3,
				awaitingRetry = true,
			)

		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(
				QuickBuildStatus.NeedsFullBuild(
					InvalidationReason.GRADLE_CONFIG_CHANGED,
					3,
					awaitingRetry = true,
				),
			)
	}

	@Test
	fun `degraded maps to reconnecting`() {
		val state = QuickBuildSessionState.Degraded(3)

		assertThat(QuickBuildStatus.from(state)).isEqualTo(QuickBuildStatus.Reconnecting(3))
	}

	@Test
	fun `a degraded session whose restart failed carries that into the status`() {
		// Without this the surface says "compile daemon restarting" while nothing is restarting
		// it, contradicting the snackbar that just said the restart failed.
		val state = QuickBuildSessionState.Degraded(3, restartFailed = true)

		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(QuickBuildStatus.Reconnecting(3, restartFailed = true))
	}

	@Test
	fun `no state maps to a transient building status except Building`() {
		val failure =
			SessionFailure.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "msg", "A.kt", 1, 1)),
			)
		val nonBuildingStates =
			listOf(
				QuickBuildSessionState.Idle(),
				QuickBuildSessionState.Provisioning(),
				QuickBuildSessionState.Ready(3),
				QuickBuildSessionState.Ready(3, lastFailure = failure),
				QuickBuildSessionState.Building(3, warmingCompiler = true),
				QuickBuildSessionState.Deployed(4, 900),
				QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 3),
				QuickBuildSessionState.Degraded(3),
			)

		nonBuildingStates.forEach { state ->
			assertThat(QuickBuildStatus.from(state))
				.isNotInstanceOf(QuickBuildStatus.Building::class.java)
		}
	}
}
