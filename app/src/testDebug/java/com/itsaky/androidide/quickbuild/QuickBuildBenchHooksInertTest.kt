package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The other half of the release-parity claim: a DEBUG build with the `CodeOnTheGo.qbbench`
 * flag absent must behave exactly like a release build, since that is what every developer
 * and every CI run actually installs.
 *
 * Robolectric because [com.itsaky.androidide.utils.FeatureFlags] reads Android's external
 * storage; nothing initializes it here, so every flag reads off - the shipping state.
 */
@RunWith(RobolectricTestRunner::class)
class QuickBuildBenchHooksInertTest {
	@Test
	fun `the benchmark interface is off unless the flag file says otherwise`() {
		assertThat(QuickBuildBenchHooks.isEnabled).isFalse()
	}

	@Test
	fun `no autostart is claimable, so the editor prebuilds and waits for a human`() {
		assertThat(QuickBuildBenchHooks.claimAutostart("/some/project")).isEqualTo(AutostartBuild.NONE)
		assertThat(AutostartBuild.NONE.suppressesPrebuild).isFalse()
	}

	@Test
	fun `a build result never suppresses the install`() {
		assertThat(
			QuickBuildBenchHooks.standardBuildEnded(isTerminal = true, isSuccess = true),
		).isFalse()
	}

	@Test
	fun `the warm compile runs and no extra metrics sink is fanned in`() {
		assertThat(QuickBuildBenchHooks.warmCompileEnabled()).isTrue()
		assertThat(QuickBuildBenchHooks.metricsSink()).isNull()
	}
}
