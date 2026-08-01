@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the debounce policy from design-watcher-and-testing.md section 3: trailing
 * [quietMillis] window, reset on every event, capped at [maxMillis] since the batch's
 * first event. Virtual-time tests so they are deterministic and instant. Batches carry
 * both the modified/created paths and the removed ones.
 */
class ChangeCoalescingTest {
	private fun f(name: String) = File("/proj/app/src/main/java/$name")

	private fun m(name: String): WatchEvent = WatchEvent.Modified(f(name))

	private fun d(name: String): WatchEvent = WatchEvent.Removed(f(name))

	@Test
	fun `a single change emits one batch after the quiet window`() =
		runTest {
			val batches =
				flowOf(m("A.kt")).coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			assertThat(batches).hasSize(1)
			assertThat(batches.single().files).containsExactly(f("A.kt"))
			assertThat(batches.single().removed).isEmpty()
		}

	@Test
	fun `writes within the quiet window coalesce into one batch`() =
		runTest {
			val source =
				flow {
					emit(m("A.kt"))
					delay(50)
					emit(m("B.kt"))
					delay(50)
					emit(m("C.kt"))
				}

			val batches = source.coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			assertThat(batches).hasSize(1)
			assertThat(batches.single().files).containsExactly(f("A.kt"), f("B.kt"), f("C.kt"))
		}

	@Test
	fun `a gap longer than the quiet window splits into two batches`() =
		runTest {
			val source =
				flow {
					emit(m("A.kt"))
					delay(300) // > quiet window: batch 1 flushes
					emit(m("B.kt"))
				}

			val batches = source.coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			assertThat(batches).hasSize(2)
			assertThat(batches[0].files).containsExactly(f("A.kt"))
			assertThat(batches[1].files).containsExactly(f("B.kt"))
		}

	@Test
	fun `a continuous stream is capped and flushes at maxMillis`() =
		runTest {
			// An event every 100 ms (< 150 quiet) for 1.5 s: the quiet timer keeps resetting,
			// so only the cap can flush. First batch must land at ~maxMillis, not at the end.
			val emitted = mutableListOf<File>()
			val source =
				flow {
					repeat(15) { i ->
						val file = f("S$i.kt")
						emitted.add(file)
						emit(WatchEvent.Modified(file))
						delay(100)
					}
				}

			val batches = source.coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			// The cap forces at least two batches out of a stream the quiet window alone
			// would never break, and no file is lost across them.
			assertThat(batches.size).isAtLeast(2)
			assertThat(batches.flatMap { it.files }.toSet()).isEqualTo(emitted.toSet())
		}

	@Test
	fun `duplicate paths in a burst collapse to one entry`() =
		runTest {
			val source =
				flow {
					emit(m("A.kt"))
					delay(20)
					emit(m("A.kt"))
					delay(20)
					emit(m("A.kt"))
				}

			val batches = source.coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			assertThat(batches).hasSize(1)
			assertThat(batches.single().files).containsExactly(f("A.kt"))
		}

	@Test
	fun `a removal is carried in the batch's removed set`() =
		runTest {
			val source =
				flow {
					emit(m("A.kt"))
					delay(20)
					emit(d("B.kt"))
				}

			val batches = source.coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			assertThat(batches).hasSize(1)
			assertThat(batches.single().files).containsExactly(f("A.kt"))
			assertThat(batches.single().removed).containsExactly(f("B.kt"))
		}

	@Test
	fun `the last event per path wins - create then delete collapses to a removal`() =
		runTest {
			val source =
				flow {
					emit(m("A.kt"))
					delay(20)
					emit(d("A.kt"))
				}

			val batches = source.coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			assertThat(batches).hasSize(1)
			assertThat(batches.single().files).isEmpty()
			assertThat(batches.single().removed).containsExactly(f("A.kt"))
		}

	@Test
	fun `the last event per path wins - delete then recreate collapses to a modification`() =
		runTest {
			val source =
				flow {
					emit(d("A.kt"))
					delay(20)
					emit(m("A.kt"))
				}

			val batches = source.coalesceChanges(quietMillis = 150, maxMillis = 1000).toList()

			assertThat(batches).hasSize(1)
			assertThat(batches.single().files).containsExactly(f("A.kt"))
			assertThat(batches.single().removed).isEmpty()
		}

	@Test
	fun `a batch is not lost when the consumer is busy at flush time`() =
		runTest {
			// The quiet-timer flush must not cancel its OWN job before send(): if send()
			// then has to suspend (consumer busy), prompt cancellation throws and the batch
			// is silently dropped - a stale app. A rendezvous buffer + a busy consumer force
			// exactly that suspension. Upstream stays open so the flush comes
			// from the timer, not the terminal path.
			val source =
				flow {
					emit(m("A.kt"))
					delay(300) // > quiet window: batch 1 flushes via its quiet timer
					emit(m("B.kt")) // batch 2's quiet timer fires while the consumer is busy
					delay(2000)
				}

			val batches = mutableListOf<ChangedFiles.Known>()
			source
				.coalesceChanges(quietMillis = 150, maxMillis = 1000)
				.buffer(Channel.RENDEZVOUS)
				.collect { batch ->
					batches.add(batch)
					delay(1000) // busy well past batch 2's timers: its send() must suspend
				}

			assertThat(batches).hasSize(2)
			assertThat(batches[0].files).containsExactly(f("A.kt"))
			assertThat(batches[1].files).containsExactly(f("B.kt"))
		}

	@Test
	fun `pending events flush when the upstream completes before the quiet window`() =
		runTest {
			// Upstream ends immediately after emitting; the terminal flush must still deliver.
			val batches =
				flowOf(f("A.kt"), f("B.kt"))
					.map<File, WatchEvent> { WatchEvent.Modified(it) }
					.coalesceChanges(quietMillis = 150, maxMillis = 1000)
					.toList()

			assertThat(batches.flatMap { it.files }.toSet()).containsExactly(f("A.kt"), f("B.kt"))
		}
}
