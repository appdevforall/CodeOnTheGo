package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Collections
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
 * Fix: [analyzeMaybeDangling] / [withAnalysisLock] hold a process-wide reentrant lock so analyses
 * are mutually exclusive.
 *
 * Both tests fail before the fix (either by throwing the exception or by observing overlapping
 * analyses) and pass after it.
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
							analyzeMaybeDangling(file) {
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
							analyzeMaybeDangling(file) {
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
}
