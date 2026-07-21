package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class QuickBuildStatusTest {
	@Test
	fun `idle maps to hidden`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Idle))
			.isEqualTo(QuickBuildStatus.Hidden)
	}

	@Test
	fun `provisioning maps to provisioning`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Provisioning))
			.isEqualTo(QuickBuildStatus.Provisioning)
	}

	@Test
	fun `background prewarming maps to hidden - the user never asked for it`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prewarming(tapQueued = false)))
			.isEqualTo(QuickBuildStatus.Hidden)
	}

	@Test
	fun `prewarming with a queued tap maps to provisioning`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prewarming(tapQueued = true)))
			.isEqualTo(QuickBuildStatus.Provisioning)
	}

	@Test
	fun `ready with no failure maps to up to date`() {
		val state = QuickBuildSessionState.Ready(3)

		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(QuickBuildStatus.UpToDate(3, buildDurationMillis = null))
	}

	// Regression for the prototype's stuck-"Compiling..." banner: an error state must
	// never map to Building.
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
	fun `degraded maps to reconnecting`() {
		val state = QuickBuildSessionState.Degraded(3)

		assertThat(QuickBuildStatus.from(state)).isEqualTo(QuickBuildStatus.Reconnecting(3))
	}

	@Test
	fun `no state maps to a transient building status except Building`() {
		val failure =
			SessionFailure.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "msg", "A.kt", 1, 1)),
			)
		val nonBuildingStates =
			listOf(
				QuickBuildSessionState.Idle,
				QuickBuildSessionState.Provisioning,
				QuickBuildSessionState.Ready(3),
				QuickBuildSessionState.Ready(3, lastFailure = failure),
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
