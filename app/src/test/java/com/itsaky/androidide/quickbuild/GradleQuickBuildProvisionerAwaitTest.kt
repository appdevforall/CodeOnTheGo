package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A tap during CoGo's Gradle sync used to fail as "Quick Build proxy app build failed",
 * because an unpopulated project model is indistinguishable from a project with no Android
 * module. The tap now queues behind the sync instead.
 */
class GradleQuickBuildProvisionerAwaitTest {
	@Test
	fun `an already-published model returns immediately without sleeping`() =
		runTest {
			var sleeps = 0

			val ready =
				GradleQuickBuildProvisioner.awaitProjectModel(
					timeoutMs = 1_000,
					pollMs = 10,
					sleep = { sleeps++ },
				) { true }

			assertThat(ready).isTrue()
			assertThat(sleeps).isEqualTo(0)
		}

	@Test
	fun `a model that appears mid-wait is picked up and reported ready`() =
		runTest {
			var polls = 0

			val ready =
				GradleQuickBuildProvisioner.awaitProjectModel(
					timeoutMs = 1_000,
					pollMs = 10,
					sleep = {},
				) { polls++ >= 3 }

			assertThat(ready).isTrue()
			// One probe before the loop plus the probes that returned false, then the true one.
			assertThat(polls).isEqualTo(4)
		}

	@Test
	fun `a model that never appears gives up at the timeout rather than waiting forever`() =
		runTest {
			var slept = 0L

			val ready =
				GradleQuickBuildProvisioner.awaitProjectModel(
					timeoutMs = 100,
					pollMs = 10,
					sleep = { slept += it },
				) { false }

			assertThat(ready).isFalse()
			assertThat(slept).isEqualTo(100)
		}

	@Test
	fun `the shipped timeout is long enough to outlast a cold low-spec sync`() {
		assertThat(GradleQuickBuildProvisioner.PROJECT_MODEL_TIMEOUT_MS).isAtLeast(60_000)
	}
}
