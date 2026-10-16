package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the one decision a shipping build depends on: the editor fires the eager Quick Build
 * prebuild on project init unless a benchmark autostart has claimed the Gradle daemon for a
 * standard build.
 *
 * A release build ships no harness, so the claim always comes back [AutostartBuild.NONE] -
 * and that value must never suppress the prebuild. A release APK that silently stopped
 * prebuilding would look identical from the outside and cost the user the whole first-tap
 * speedup, so the predicate is asserted directly rather than left to the call site.
 */
class QuickBuildPrebuildDecisionTest {
	@Test
	fun `nothing armed - the only case a release build can reach - prebuilds`() {
		assertThat(AutostartBuild.NONE.suppressesPrebuild).isFalse()
	}

	@Test
	fun `a quick-build autostart still prebuilds`() {
		assertThat(AutostartBuild.QUICK_BUILD.suppressesPrebuild).isFalse()
	}

	@Test
	fun `a standard autostart suppresses the prebuild`() {
		assertThat(AutostartBuild.STANDARD.suppressesPrebuild).isTrue()
	}

	@Test
	fun `no autostart other than the standard build suppresses the prebuild`() {
		// Exhaustive, so a value added later has to state its intent here rather than
		// inherit whichever answer the predicate happens to give it.
		assertThat(AutostartBuild.entries.filter { it.suppressesPrebuild })
			.containsExactly(AutostartBuild.STANDARD)
	}
}
