package org.appdevforall.cotg.quickbuild.domain.classify

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The [recompilesCode] classification, one case per route.
 *
 * Its only production caller gates the stale-classes guard (`QuickBuildSessionManager`:
 * `if (event.route is BuildRoute.WarmCompile || !event.route.recompilesCode) return`), so a
 * route on the wrong side silently skips a guard that should run or runs one that should not.
 */
class BuildRouteTest {
	@Test
	fun `every route that produces class files reports recompilesCode`() {
		assertThat(BuildRoute.CodeOnly.recompilesCode).isTrue()
		assertThat(BuildRoute.CodeAndResources.recompilesCode).isTrue()
		// NoOp still runs the compiler - it is "compiled and nothing moved", not "skipped".
		assertThat(BuildRoute.NoOp.recompilesCode).isTrue()
		// WarmCompile recompiles but never deploys; callers reasoning about the running
		// app must exclude it separately, which is why it is true here.
		assertThat(BuildRoute.WarmCompile.recompilesCode).isTrue()
	}

	@Test
	fun `routes that move no class file do not report recompilesCode`() {
		assertThat(BuildRoute.ResourcesOnly.recompilesCode).isFalse()
		assertThat(BuildRoute.AssetsOnly.recompilesCode).isFalse()
		assertThat(BuildRoute.FullGradleBuild(InvalidationReason.MANIFEST_CHANGED).recompilesCode).isFalse()
	}

	/**
	 * The classification is a property of the route alone: a full Gradle build hands the
	 * whole job to Gradle whatever invalidated the baseline, so no reason may flip it.
	 */
	@Test
	fun `no invalidation reason makes a full gradle build recompile on the live path`() {
		InvalidationReason.entries.forEach { reason ->
			assertThat(BuildRoute.FullGradleBuild(reason).recompilesCode).isFalse()
		}
	}
}
