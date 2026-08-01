package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class QuickBuildToneTest {
	@Test
	fun `hidden and up-to-date map to READY`() {
		assertThat(QuickBuildStatus.Hidden.toTone()).isEqualTo(QuickBuildTone.READY)
		assertThat(QuickBuildStatus.UpToDate(1, null).toTone()).isEqualTo(QuickBuildTone.READY)
		assertThat(QuickBuildStatus.UpToDate(1, 500).toTone()).isEqualTo(QuickBuildTone.READY)
	}

	@Test
	fun `provisioning and building map to BUILDING`() {
		assertThat(QuickBuildStatus.Provisioning.toTone()).isEqualTo(QuickBuildTone.BUILDING)
		assertThat(QuickBuildStatus.Building(1).toTone()).isEqualTo(QuickBuildTone.BUILDING)
	}

	// Behaviour 1 draws a line: the button offers a stop for builds the USER started, and
	// keeps the bolt for the two background builds they did not. These are the derivations
	// that decide it, so they are pinned rather than left to be re-decided by accident.
	@Test
	fun `the background warm compile reads as READY - it deploys nothing and was never asked for`() {
		val warmCompiling = QuickBuildSessionState.Building(3, warmingCompiler = true)

		assertThat(QuickBuildStatus.from(warmCompiling).toTone()).isEqualTo(QuickBuildTone.READY)
	}

	@Test
	fun `an unasked-for prebuild reads as READY, but a prebuild with a queued tap reads as BUILDING`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prebuilding()).toTone())
			.isEqualTo(QuickBuildTone.READY)
		// Once a tap is queued the user IS waiting on this build, so the stop belongs to them.
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prebuilding(tapQueued = true)).toTone())
			.isEqualTo(QuickBuildTone.BUILDING)
	}

	@Test
	fun `a real quick build reads as BUILDING so the button becomes the stop button`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Building(3)).toTone())
			.isEqualTo(QuickBuildTone.BUILDING)
	}

	@Test
	fun `failed, needs-full-build and reconnecting map to ATTENTION`() {
		val failure = SessionFailure.DeployError("boom")
		assertThat(QuickBuildStatus.Failed(1, failure).toTone()).isEqualTo(QuickBuildTone.ATTENTION)
		assertThat(
			QuickBuildStatus.NeedsFullBuild(InvalidationReason.MANIFEST_CHANGED, 1).toTone(),
		).isEqualTo(QuickBuildTone.ATTENTION)
		assertThat(QuickBuildStatus.Reconnecting(1).toTone()).isEqualTo(QuickBuildTone.ATTENTION)
	}
}
