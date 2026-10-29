package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.watch.WatchFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Lifecycle and choke-point edges of [AndroidProjectWatcher] beyond
 * [AndroidProjectWatcherTest]'s pipeline cases, driven through the same JVM seams
 * ([AndroidProjectWatcher.report] / [AndroidProjectWatcher.sweep]) on the same virtual clock.
 */
class AndroidProjectWatcherEdgeTest {
	@TempDir lateinit var tempDir: File

	private fun TestScope.startWatcher(
		root: File,
		batches: MutableList<ChangedFiles.Known>,
		pollIntervalMillis: Long = 3_600_000L, // parked; sweeps are driven manually
	): AndroidProjectWatcher {
		val watcher =
			AndroidProjectWatcher(
				watchedRoots = listOf(root),
				watchedFiles = emptyList(),
				filter = WatchFilter(listOf(root)),
				// backgroundScope so the never-ending poll job is cancelled with the test.
				scope = backgroundScope,
				pollIntervalMillis = pollIntervalMillis,
				quietMillis = QUIET_MILLIS,
				maxMillis = MAX_MILLIS,
				pollDispatcher = StandardTestDispatcher(testScheduler),
			)
		watcher.start(batches::add)
		// Prime the poll's baseline before the test touches anything.
		runCurrent()
		return watcher
	}

	/** Advances past the quiet window and the cap, so every pending batch has been emitted. */
	private fun TestScope.settle() {
		advanceTimeBy(MAX_MILLIS + 1)
		runCurrent()
	}

	@Test
	fun `stop before start is a safe no-op`() =
		runTest {
			val watcher =
				AndroidProjectWatcher(
					watchedRoots = listOf(tempDir),
					watchedFiles = emptyList(),
					filter = WatchFilter(listOf(tempDir)),
					scope = backgroundScope,
				)

			// Nothing was started; stop must not throw on the never-armed jobs.
			watcher.stop()
		}

	@Test
	fun `a directory event is dropped at the choke point - never a compile input`() =
		runTest {
			val root = File(tempDir, "proj").apply { mkdirs() }
			val srcDir = File(root, "app/src/main/java/com/example").apply { mkdirs() }
			val source = File(srcDir, "Foo.kt").apply { writeText("class Foo") }
			val batches = mutableListOf<ChangedFiles.Known>()
			val watcher = startWatcher(root, batches)

			// A directory "change" (as inotify would deliver for a mkdir) must not emit...
			watcher.report(srcDir, fromPoll = false)
			// ...while a real file change right after emits normally.
			watcher.report(source, fromPoll = false)
			settle()

			assertThat(batches.single().files).containsExactly(source)
		}

	@Test
	fun `the automatic poll loop sweeps a change to a batch without a manual sweep`() =
		runTest {
			val root = File(tempDir, "proj").apply { mkdirs() }
			val source =
				File(root, "app/src/main/java/com/example/Foo.kt").apply {
					parentFile!!.mkdirs()
					writeText("class Foo")
				}
			val batches = mutableListOf<ChangedFiles.Known>()
			// A real, running loop - this is the case under test, so its interval is live.
			val pollIntervalMillis = 50L
			startWatcher(root, batches, pollIntervalMillis)

			// A content change the (inert) inotify path never reports: only the poll's own
			// recurring sweep can deliver it.
			source.writeText("class Foo { val added = 1 }")
			advanceTimeBy(pollIntervalMillis + 1)
			settle()

			assertThat(batches.single().files).containsExactly(source)
		}

	@Test
	fun `a poll observation of an unchanged file stays quiet`() =
		runTest {
			val root = File(tempDir, "proj").apply { mkdirs() }
			val batches = mutableListOf<ChangedFiles.Known>()
			val watcher = startWatcher(root, batches)
			// Created after the baseline priming, so it is a genuinely NEW path to the poll.
			val source =
				File(root, "app/src/main/java/com/example/Foo.kt").apply {
					parentFile!!.mkdirs()
					writeText("class Foo")
				}

			// First poll sighting of the new path records the fingerprint and emits...
			watcher.report(source, fromPoll = true)
			settle()
			assertThat(batches).hasSize(1)

			// ...but a second sweep over the untouched file must NOT re-emit (the
			// fingerprint gate is what keeps the hybrid from double-building).
			watcher.report(source, fromPoll = true)
			settle()

			assertThat(batches).hasSize(1)
		}

	@Test
	fun `a create event that lands after stop registers no watches`() =
		runTest {
			val root = File(tempDir, "proj").apply { mkdirs() }
			val watcher = startWatcher(root, mutableListOf())

			watcher.stop()

			// The FileObserver thread's CREATE callback blocks on the observers lock during
			// stop() and wakes after the clear. Registering here would arm a tree stop() has
			// already finished with, so nothing could ever stop those watches again.
			File(root, "main/java/com/example").apply { mkdirs() }
			watcher.registerCreatedTree(File(root, "main"))

			assertThat(watcher.watchCount()).isEqualTo(0)
		}

	@Test
	fun `start clears the stopped latch so a reopened project watches again`() =
		runTest {
			val root = File(tempDir, "proj").apply { mkdirs() }
			val watcher = startWatcher(root, mutableListOf())
			watcher.stop()

			// Same instance, restarted: the latch stop() set has to clear, or a reopened
			// project silently never picks up a directory created during the session.
			val batches = mutableListOf<ChangedFiles.Known>()
			watcher.start(batches::add)
			runCurrent()
			val before = watcher.watchCount()
			File(root, "main/java").apply { mkdirs() }
			watcher.registerCreatedTree(File(root, "main"))

			// main + main/java.
			assertThat(watcher.watchCount() - before).isEqualTo(2)

			// A change after the restart has to reach the pipeline: stop() closed the raw
			// channel, so a restart that kept it would drop every event on the floor.
			val source = File(root, "main/java/A.kt").apply { writeText("class A") }
			watcher.report(source, fromPoll = false)
			settle()
			assertThat(batches.single().files).containsExactly(source)
		}

	@Test
	fun `a batch handler that throws does not end the watch`() =
		runTest {
			val root = File(tempDir, "proj").apply { mkdirs() }
			val batches = mutableListOf<ChangedFiles.Known>()
			val watcher =
				AndroidProjectWatcher(
					watchedRoots = listOf(root),
					watchedFiles = emptyList(),
					filter = WatchFilter(listOf(root)),
					scope = backgroundScope,
					pollIntervalMillis = 3_600_000L,
					quietMillis = QUIET_MILLIS,
					maxMillis = MAX_MILLIS,
					pollDispatcher = StandardTestDispatcher(testScheduler),
				)
			var calls = 0
			watcher.start { batch ->
				calls++
				if (calls == 1) throw IllegalStateException("handler failed on the first batch")
				batches += batch
			}
			runCurrent()

			val first = File(root, "A.kt").apply { writeText("class A") }
			watcher.report(first, fromPoll = false)
			settle()
			// The collector is the channel's only consumer; a throw that ended it would
			// leave every later save feeding a channel nobody drains.
			val second = File(root, "B.kt").apply { writeText("class B") }
			watcher.report(second, fromPoll = false)
			settle()

			assertThat(calls).isEqualTo(2)
			assertThat(batches.single().files).containsExactly(second)
		}

	@Test
	fun `cancelling the scope releases the observers like stop does`() =
		runTest {
			val root = File(tempDir, "proj").apply { mkdirs() }
			val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
			val watcher =
				AndroidProjectWatcher(
					watchedRoots = listOf(root),
					watchedFiles = emptyList(),
					filter = WatchFilter(listOf(root)),
					scope = scope,
					pollIntervalMillis = 3_600_000L,
					quietMillis = QUIET_MILLIS,
					maxMillis = MAX_MILLIS,
					pollDispatcher = StandardTestDispatcher(testScheduler),
				)
			watcher.start {}
			runCurrent()
			assertThat(watcher.watchCount()).isEqualTo(1)

			// The scope's Job completes once its cancelled children have run their
			// cancellation on the dispatcher, which is when the completion handler fires.
			scope.cancel()
			runCurrent()

			assertThat(watcher.watchCount()).isEqualTo(0)
		}

	private companion object {
		private const val QUIET_MILLIS = 50L
		private const val MAX_MILLIS = 1_000L
	}
}
