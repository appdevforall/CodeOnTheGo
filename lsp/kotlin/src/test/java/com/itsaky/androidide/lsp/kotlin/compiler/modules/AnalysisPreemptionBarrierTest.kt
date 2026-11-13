package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.progress.ICancelChecker
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * An edit's write must not wait behind a running or queued analysis: while
 * [AnalysisScheduler.preemptingAnalysis] runs, the holder is preempted and every other requester
 * leaves with [AnalysisPreemptedException], releasing the read lock the write needs.
 */
class AnalysisPreemptionBarrierTest {
	private fun checker() = ScheduledCancelChecker(ICancelChecker.NOOP)

	@Test(timeout = 10_000)
	fun `raising the barrier preempts the running holder`() {
		val held = CountDownLatch(1)
		val release = CountDownLatch(1)
		val preempted = CountDownLatch(1)
		val holder =
			thread {
				AnalysisScheduler.withLock(AnalysisPriority.INDEXING, checker(), onPreempt = { preempted.countDown() }) {
					held.countDown()
					release.await(5, SECONDS)
				}
			}
		try {
			assertThat(held.await(5, SECONDS)).isTrue()

			AnalysisScheduler.preemptingAnalysis { }

			assertThat(preempted.await(1, SECONDS)).isTrue()
		} finally {
			release.countDown()
			holder.join()
		}
	}

	@Test(timeout = 10_000)
	fun `a requester waiting for the lock leaves when the barrier goes up`() {
		val held = CountDownLatch(1)
		val release = CountDownLatch(1)
		// The waiter preempts the INDEXING holder from inside acquire's loop, so this marks it queued.
		val waiterQueued = CountDownLatch(1)
		val waiterThrew = CountDownLatch(1)
		val waiterError = AtomicReference<Throwable?>()
		val holder =
			thread {
				AnalysisScheduler.withLock(AnalysisPriority.INDEXING, checker(), onPreempt = { waiterQueued.countDown() }) {
					held.countDown()
					release.await(5, SECONDS)
				}
			}
		var waiter: Thread? = null
		try {
			assertThat(held.await(5, SECONDS)).isTrue()
			waiter =
				thread {
					try {
						AnalysisScheduler.withLock(AnalysisPriority.DIAGNOSTICS, checker(), onPreempt = {}) { }
					} catch (e: Throwable) {
						waiterError.set(e)
						waiterThrew.countDown()
					}
				}
			assertThat(waiterQueued.await(5, SECONDS)).isTrue()

			val waiterLeft = AnalysisScheduler.preemptingAnalysis { waiterThrew.await(1, SECONDS) }

			assertThat(waiterLeft).isTrue()
			assertThat(waiterError.get()).isInstanceOf(AnalysisPreemptedException::class.java)
		} finally {
			release.countDown()
			holder.join()
			waiter?.join()
		}
	}

	@Test(timeout = 10_000)
	fun `requests after the barrier comes down proceed normally`() {
		AnalysisScheduler.preemptingAnalysis { }

		val result = AnalysisScheduler.withLock(AnalysisPriority.INTERACTIVE, checker(), onPreempt = {}) { 42 }

		assertThat(result).isEqualTo(42)
	}

	@Test(timeout = 10_000)
	fun `the barrier is lowered when preempting the holder throws`() {
		val held = CountDownLatch(1)
		val release = CountDownLatch(1)
		val holder =
			thread {
				AnalysisScheduler.withLock(
					AnalysisPriority.INDEXING,
					checker(),
					onPreempt = { throw IllegalStateException("onPreempt failed") },
				) {
					held.countDown()
					release.await(5, SECONDS)
				}
			}
		try {
			assertThat(held.await(5, SECONDS)).isTrue()
			runCatching { AnalysisScheduler.preemptingAnalysis { } }
		} finally {
			release.countDown()
			holder.join()
		}

		val result = AnalysisScheduler.withLock(AnalysisPriority.INTERACTIVE, checker(), onPreempt = {}) { 42 }

		assertThat(result).isEqualTo(42)
	}

	@Test(timeout = 10_000)
	fun `the barrier is lowered when the block throws`() {
		runCatching { AnalysisScheduler.preemptingAnalysis { throw IllegalStateException("block failed") } }

		val result = AnalysisScheduler.withLock(AnalysisPriority.INTERACTIVE, checker(), onPreempt = {}) { 42 }

		assertThat(result).isEqualTo(42)
	}
}
