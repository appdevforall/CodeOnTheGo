package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_VERSION_CODE_OVERRIDE
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * JVM-pure checks for QuickBuildPlugin's property parsing. The full setup-build behavior
 * (suffix skipped, versionCode applied to outputs) is TestKit territory and rides the
 * existing functional-suite environment.
 */
class QuickBuildPluginTest {
	@Test
	fun `versionCodeOverride is null when the property is unset`() {
		assertThat(QuickBuildPlugin.resolveVersionCodeOverride(null)).isNull()
	}

	@Test
	fun `versionCodeOverride parses a -P string value`() {
		assertThat(QuickBuildPlugin.resolveVersionCodeOverride("12346")).isEqualTo(12346)
	}

	@Test
	fun `versionCodeOverride accepts a numeric value`() {
		assertThat(QuickBuildPlugin.resolveVersionCodeOverride(42)).isEqualTo(42)
	}

	@Test
	fun `versionCodeOverride fails loud on a non-numeric value`() {
		val error =
			assertThrows<GradleException> {
				QuickBuildPlugin.resolveVersionCodeOverride("not-a-number")
			}
		assertThat(error).hasMessageThat().contains(PROPERTY_QUICK_BUILD_VERSION_CODE_OVERRIDE)
		assertThat(error).hasMessageThat().contains("not-a-number")
	}

	@Test
	fun `versionCodeOverride fails loud on zero and negative values`() {
		listOf("0", "-1").forEach { raw ->
			val error =
				assertThrows<GradleException> {
					QuickBuildPlugin.resolveVersionCodeOverride(raw)
				}
			assertThat(error).hasMessageThat().contains(PROPERTY_QUICK_BUILD_VERSION_CODE_OVERRIDE)
		}
	}
}
