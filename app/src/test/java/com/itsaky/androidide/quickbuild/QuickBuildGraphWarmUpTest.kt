package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildSessionManager
import org.junit.Test

/**
 * The ordering [QuickBuildGraphWarmUp] exists to enforce: a main-thread read never builds the
 * graph. Every test counts resolver calls, because "the graph was built on the main thread" is
 * exactly one resolver call from the wrong place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickBuildGraphWarmUpTest {
	private val manager = mockk<QuickBuildSessionManager>()
	private var resolves = 0
	private var resolveResult: QuickBuildSessionManager? = manager
	private var enabled = true

	private val warmUp =
		QuickBuildGraphWarmUp(
			isEnabled = { enabled },
			resolve = {
				resolves++
				resolveResult
			},
		)

	@Test
	fun `reading before the warm-up returns null and never resolves`() {
		assertThat(warmUp.sessionManagerOrNull).isNull()
		assertThat(warmUp.sessionManagerOrNull).isNull()
		assertThat(resolves).isEqualTo(0)
	}

	@Test
	fun `the warm-up resolves once and every later read finds the built manager`() {
		assertThat(warmUp.warmUp()).isSameInstanceAs(manager)
		assertThat(warmUp.sessionManagerOrNull).isSameInstanceAs(manager)
		assertThat(warmUp.warmUp()).isSameInstanceAs(manager)
		assertThat(resolves).isEqualTo(1)
	}

	@Test
	fun `a failed resolve leaves nothing built and the next warm-up retries`() {
		resolveResult = null
		assertThat(warmUp.warmUp()).isNull()
		assertThat(warmUp.sessionManagerOrNull).isNull()

		resolveResult = manager
		assertThat(warmUp.warmUp()).isSameInstanceAs(manager)
		assertThat(resolves).isEqualTo(2)
	}

	@Test
	fun `with the feature off nothing resolves and every read is null`() {
		enabled = false
		assertThat(warmUp.warmUp()).isNull()
		assertThat(warmUp.sessionManagerOrNull).isNull()
		assertThat(resolves).isEqualTo(0)
	}

	@Test
	fun `await hands out the manager once the warm-up has built it`() =
		runTest {
			val awaited = async { warmUp.await() }
			assertThat(awaited.isCompleted).isFalse()

			warmUp.warmUp()

			assertThat(awaited.await()).isSameInstanceAs(manager)
		}

	@Test
	fun `await returns null at once when the feature is off`() =
		runTest {
			enabled = false
			assertThat(warmUp.await()).isNull()
			assertThat(resolves).isEqualTo(0)
		}
}
