package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression coverage for ADFA-4128 Bug 3: the proxy app build's task path used to be
 * composed as `"${module.path}:assembleDebug"`, which produces `::assembleDebug` for
 * a root/single-module project (Gradle path `:`) - a task path Gradle's selector
 * rejects with `TaskSelectionException`.
 */
class QuickBuildTaskPathsTest {
	@Test
	fun `top-level app module gets a single colon separator`() {
		assertThat(QuickBuildTaskPaths.assembleDebug(":app")).isEqualTo(":app:assembleDebug")
	}

	@Test
	fun `nested module path composes correctly`() {
		assertThat(QuickBuildTaskPaths.assembleDebug(":feature:home"))
			.isEqualTo(":feature:home:assembleDebug")
	}

	@Test
	fun `root module path does not double the leading colon`() {
		assertThat(QuickBuildTaskPaths.assembleDebug(":")).isEqualTo(":assembleDebug")
	}

	@Test
	fun `blank module path is treated as the root module`() {
		assertThat(QuickBuildTaskPaths.assembleDebug("")).isEqualTo(":assembleDebug")
	}
}
