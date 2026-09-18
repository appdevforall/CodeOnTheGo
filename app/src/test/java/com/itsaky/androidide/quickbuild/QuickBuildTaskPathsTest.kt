package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Two regressions are pinned here.
 *
 * The task path must not be composed as `"${module.path}:assembleDebug"`: that yields
 * `::assembleDebug` for a root/single-module project (Gradle path `:`) - a task path Gradle's
 * selector rejects with `TaskSelectionException`.
 *
 * And it must name the SELECTED VARIANT rather than the flavor-agnostic `assembleDebug`
 * lifecycle task: on a flavored project that lifecycle task builds every flavor's debug
 * variant, so CoGo would install whichever flavor's report landed last - under an
 * applicationId suffix the user never chose.
 */
class QuickBuildTaskPathsTest {
	@Test
	fun `top-level app module gets a single colon separator`() {
		assertThat(QuickBuildTaskPaths.assembleVariant(":app", "debug"))
			.isEqualTo(":app:assembleDebug")
	}

	@Test
	fun `nested module path composes correctly`() {
		assertThat(QuickBuildTaskPaths.assembleVariant(":feature:home", "debug"))
			.isEqualTo(":feature:home:assembleDebug")
	}

	@Test
	fun `root module path does not double the leading colon`() {
		assertThat(QuickBuildTaskPaths.assembleVariant(":", "debug")).isEqualTo(":assembleDebug")
	}

	@Test
	fun `blank module path is treated as the root module`() {
		assertThat(QuickBuildTaskPaths.assembleVariant("", "debug")).isEqualTo(":assembleDebug")
	}

	@Test
	fun `a flavored variant names that flavor's assemble task, not the lifecycle task`() {
		assertThat(QuickBuildTaskPaths.assembleVariant(":app", "demoDebug"))
			.isEqualTo(":app:assembleDemoDebug")
	}

	@Test
	fun `a multi-dimension variant keeps its inner camel case`() {
		// AGP uppercases only the first letter: "freeArm64Debug" -> "assembleFreeArm64Debug".
		assertThat(QuickBuildTaskPaths.assembleVariant(":app", "freeArm64Debug"))
			.isEqualTo(":app:assembleFreeArm64Debug")
	}

	@Test
	fun `a flavored variant on a root module still gets one colon`() {
		assertThat(QuickBuildTaskPaths.assembleVariant(":", "demoDebug"))
			.isEqualTo(":assembleDemoDebug")
	}

	@Test
	fun `an unknown variant falls back to the default debug variant`() {
		// The provisioner's `getSelectedVariant()?.name ?: DEFAULT_VARIANT` can only hand over a
		// name or the default, but a blank one must never compose ":app:assemble".
		assertThat(QuickBuildTaskPaths.assembleVariant(":app", "")).isEqualTo(":app:assembleDebug")
		assertThat(QuickBuildTaskPaths.assembleVariant(":app")).isEqualTo(":app:assembleDebug")
	}

	@Test
	fun `a custom build type is composed as-is`() {
		assertThat(QuickBuildTaskPaths.assembleVariant(":app", "staging"))
			.isEqualTo(":app:assembleStaging")
	}

	@Test
	fun `the report path is variant-scoped, matching where the Gradle plugin writes it`() {
		// Both halves of the plugin contract: `build/quickbuild/<variant>/setup.json`. A
		// flavor-agnostic path here would read another flavor's report - the wrong APK and
		// the wrong applicationId.
		assertThat(QuickBuildTaskPaths.setupJson("debug"))
			.isEqualTo("build/quickbuild/debug/setup.json")
		assertThat(QuickBuildTaskPaths.setupJson("demoDebug"))
			.isEqualTo("build/quickbuild/demoDebug/setup.json")
	}

	@Test
	fun `a blank variant reads the default variant's report`() {
		assertThat(QuickBuildTaskPaths.setupJson("")).isEqualTo("build/quickbuild/debug/setup.json")
		assertThat(QuickBuildTaskPaths.setupJson()).isEqualTo("build/quickbuild/debug/setup.json")
	}
}
