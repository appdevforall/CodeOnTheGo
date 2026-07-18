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
