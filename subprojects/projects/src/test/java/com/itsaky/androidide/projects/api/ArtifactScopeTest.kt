package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.models.ArtifactScope
import org.junit.Test

class ArtifactScopeTest {
	private val all =
		listOf(
			ArtifactScope.Main,
			ArtifactScope.TestFixtures,
			ArtifactScope.UNIT_TEST,
			ArtifactScope.SCREENSHOT_TEST,
			ArtifactScope.ANDROID_TEST,
		)

	@Test
	fun `fromKey round-trips every scope's key`() {
		for (scope in all) {
			assertThat(ArtifactScope.fromKey(scope.key)).isEqualTo(scope)
		}
	}

	@Test
	fun `known scopes use the keys AGP's sync model uses`() {
		assertThat(ArtifactScope.UNIT_TEST.key).isEqualTo("host:_unit_test_")
		assertThat(ArtifactScope.SCREENSHOT_TEST.key).isEqualTo("host:_screenshot_test_")
		assertThat(ArtifactScope.ANDROID_TEST.key).isEqualTo("device:_android_test_")
	}
}
