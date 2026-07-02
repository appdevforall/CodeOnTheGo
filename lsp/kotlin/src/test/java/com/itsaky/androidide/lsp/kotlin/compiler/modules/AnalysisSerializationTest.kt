package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Regression tests for the `KaInaccessibleLifetimeOwnerAccessException: ... Called outside an
 * `analyze` context.` reported in Sentry (APPDEVFORALL-VR / 7454434587).
 *
 * Root cause: indexing, diagnostics and completion all drove the stock Kotlin Analysis API
 * concurrently from `Dispatchers.Default` threads (the modified-file indexer is debounced into
 * independent coroutines), frequently against the same file. The Analysis API tracks its `analyze`
 * lifetime context in a per-thread stack and is not safe to run concurrently without platform
 * read-action coordination (which this LSP replaces with a shared read lock that does not serialize
 * analysis). Overlapping `analyze` calls corrupted the lifetime/session lifecycle.
 *
 * Fix: [analyzeMaybeDangling] / [withAnalysisLock] route through [AnalysisScheduler], a process-wide
 * priority-aware, preemptive, reentrant lock so analyses are mutually exclusive.
 *
 * The first two tests cover serialization (they fail before the fix, either by throwing the exception
 * or by observing overlapping analyses). The remaining tests cover the scheduler's priority,
 * preemption, and reentrancy behaviour.
 */
class AnalysisSerializationTest : KtLspTest() {

	@Test
	fun `concurrent analyzeMaybeDangling never throws lifetime exception`(): Unit = runBlocking {
		val files = (0 until 8).map { i ->
			createSourceFile(
				"Concurrent$i.kt",
				"""
				class Klass$i {
					fun member$i(p: Int): Int = p + $i
					val prop$i: String = "v$i"
				}

				fun topLevel$i() = $i
				""".trimIndent()
			)
		}

		val errors = Collections.synchronizedList(mutableListOf<Throwable>())

		// Many short, overlapping analyses on a high-parallelism dispatcher to reproduce the race.
		coroutineScope {
			repeat(240) { iter ->
				launch(Dispatchers.IO) {
					val file = files[iter % files.size]
					try {
						env.project.read {
							analyzeMaybeDangling(
								file,
								AnalysisPriority.DIAGNOSTICS,
								ScheduledCancelChecker(ICancelChecker.NOOP),
							) {
								// Touching declaration symbols is what triggered the lifetime check.
								file.declarations.forEach { dcl ->
									dcl.symbol
								}
							}
						}
					} catch (t: Throwable) {
						errors.add(t)
					}
				}
			}
		}

		assertThat(errors).isEmpty()
	}

	@Test
	fun `analyzeMaybeDangling serializes overlapping analyses`(): Unit = runBlocking {
		val files = (0 until 8).map { i ->
			createSourceFile("Serialized$i.kt", "class S$i { fun f$i() = $i }")
		}

		val inFlight = AtomicInteger(0)
		val maxObserved = AtomicInteger(0)
		val errors = Collections.synchronizedList(mutableListOf<Throwable>())

		coroutineScope {
			repeat(64) { iter ->
				launch(Dispatchers.IO) {
					val file = files[iter % files.size]
					try {
						env.project.read {
							analyzeMaybeDangling(
								file,
								AnalysisPriority.DIAGNOSTICS,
								ScheduledCancelChecker(ICancelChecker.NOOP),
							) {
								val concurrent = inFlight.incrementAndGet()
								maxObserved.updateAndGet { max(it, concurrent) }
								try {
									file.declarations.forEach { it.symbol }
									// Widen the window so any real overlap is observed.
									Thread.sleep(2)
								} finally {
									inFlight.decrementAndGet()
								}
							}
						}
					} catch (t: Throwable) {
						errors.add(t)
					}
				}
			}
		}

		assertThat(errors).isEmpty()
		// The shared analysis lock must prevent two analyses from running at once.
		assertThat(maxObserved.get()).isEqualTo(1)
	}

	@Test(timeout = 10_000)
	fun `reentrant withAnalysisLock on the same thread does not deadlock`() {
		var innerRan = false
		withAnalysisLock(AnalysisPriority.DIAGNOSTICS, ScheduledCancelChecker(ICancelChecker.NOOP)) {
			withAnalysisLock(AnalysisPriority.COMPLETION, ScheduledCancelChecker(ICancelChecker.NOOP)) {
				innerRan = true
			}
		}
		assertThat(innerRan).isTrue()
	}

	@Test(timeout = 10_000)
	fun `higher priority request preempts a lower priority holder`() {
		val holderChecker = ScheduledCancelChecker(ICancelChecker.NOOP)
		val holding = CountDownLatch(1)
		val preempted = AtomicBoolean(false)
		val higherRan = AtomicBoolean(false)

		// Low-priority (indexing) holder runs a long, cooperatively-cancellable analysis.
		val lower = Thread {
			try {
				withAnalysisLock(AnalysisPriority.INDEXING, holderChecker) {
					holding.countDown()
					repeat(2_000) {
						holderChecker.abortIfCancelled()
						Thread.sleep(5)
					}
				}
			} catch (e: AnalysisPreemptedException) {
				preempted.set(true)
			}
		}
		lower.start()
		assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue()

		// A completion request must preempt the in-progress indexing.
		val higher = Thread {
			withAnalysisLock(AnalysisPriority.COMPLETION, ScheduledCancelChecker(ICancelChecker.NOOP)) {
				higherRan.set(true)
			}
		}
		higher.start()
		higher.join(5_000)
		lower.join(5_000)

		assertThat(preempted.get()).isTrue()
		assertThat(higherRan.get()).isTrue()
	}

	@Test(timeout = 10_000)
	fun `analysis is interrupted mid-analyze at the compiler's ProgressManager checkpoint`() {
		// The Kotlin Analysis API calls ProgressManager.checkCanceled() densely during FIR
		// resolution, but never the LSP-level ICancelChecker.abortIfCancelled(). This body mimics
		// that: it only polls ProgressManager.checkCanceled(). Before withAnalysisLock installed a
		// CancelCheckerProgressIndicator, that call was inert (no indicator => the manager's
		// check-cancelled behaviour stayed disabled) and the work ran to completion regardless of
		// preemption. It must now be interruptible.
		val holderChecker = ScheduledCancelChecker(ICancelChecker.NOOP)
		val holding = CountDownLatch(1)
		val preempted = AtomicBoolean(false)
		val ranToCompletion = AtomicBoolean(false)

		val lower = Thread {
			try {
				withAnalysisLock(AnalysisPriority.INDEXING, holderChecker) {
					holding.countDown()
					repeat(2_000) {
						// Compiler-level checkpoint only — no abortIfCancelled() here.
						ProgressManager.checkCanceled()
						Thread.sleep(5)
					}
					ranToCompletion.set(true)
				}
			} catch (e: AnalysisPreemptedException) {
				preempted.set(true)
			}
		}
		lower.start()
		assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue()

		// A completion request preempts the in-progress (indexing) analysis.
		val higher = Thread {
			withAnalysisLock(AnalysisPriority.COMPLETION, ScheduledCancelChecker(ICancelChecker.NOOP)) {}
		}
		higher.start()
		higher.join(5_000)
		lower.join(5_000)

		assertThat(preempted.get()).isTrue()
		assertThat(ranToCompletion.get()).isFalse()
	}

	@Test(timeout = 10_000)
	fun `lower priority request waits while a higher priority holder runs`() {
		val holding = CountDownLatch(1)
		val release = CountDownLatch(1)
		val lowerEntered = AtomicBoolean(false)

		// High-priority (completion) holder holds the lock until released.
		val higher = Thread {
			withAnalysisLock(AnalysisPriority.COMPLETION, ScheduledCancelChecker(ICancelChecker.NOOP)) {
				holding.countDown()
				release.await()
			}
		}
		higher.start()
		assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue()

		// A diagnostics request is strictly lower priority: it must not preempt completion.
		val lower = Thread {
			withAnalysisLock(AnalysisPriority.DIAGNOSTICS, ScheduledCancelChecker(ICancelChecker.NOOP)) {
				lowerEntered.set(true)
			}
		}
		lower.start()

		// Give the lower-priority request time to (incorrectly) barge in.
		Thread.sleep(300)
		val enteredWhileHeld = lowerEntered.get()

		release.countDown()
		higher.join(5_000)
		lower.join(5_000)

		assertThat(enteredWhileHeld).isFalse()
		assertThat(lowerEntered.get()).isTrue()
	}
}
