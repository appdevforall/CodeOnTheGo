@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The completion-flush guarantee of [coalesceChanges]: a watcher stream that ends
 * mid-batch (session teardown) must still deliver the pending batch instead of
 * dropping it - the never-stale invariant's last line.
 */
class ChangeCoalescingEdgeTest {
	private fun f(name: String) = File("/proj/app/src/main/java/$name")

	@Test
	fun `upstream completion flushes the pending batch without waiting for the quiet window`() =
		runTest {
			val batches =
				flowOf(
					WatchEvent.Modified(f("A.kt")),
					WatchEvent.Removed(f("B.kt")),
				).coalesceChanges(quietMillis = 60_000, maxMillis = 600_000).toList()

			// Both timers are still armed (their windows are enormous); only the
			// upstream's completion can have delivered this batch.
			assertThat(batches).hasSize(1)
			assertThat(batches[0].files).containsExactly(f("A.kt"))
			assertThat(batches[0].removed).containsExactly(f("B.kt"))
		}

	@Test
	fun `an empty upstream completes with no batch`() =
		runTest {
			val batches =
				flowOf<WatchEvent>()
					.coalesceChanges(quietMillis = 10, maxMillis = 100)
					.toList()

			assertThat(batches).isEmpty()
		}
}
