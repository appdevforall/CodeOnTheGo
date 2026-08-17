package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
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
 * JVM tests for the watcher's poll/coalesce pipeline. FileObserver is inert on the JVM, so
 * inotify deliveries are simulated via [AndroidProjectWatcher.report] and sweeps driven via
 * [AndroidProjectWatcher.sweep], on the virtual clock: a "stayed quiet" assertion means the
 * pipeline had nothing left to do. Regression pinned: `adb push` back-dates mtime after
 * CLOSE_WRITE, so the next sweep emits a phantom second batch - a duplicate rebaseline.
 */
class AndroidProjectWatcherTest {
	@TempDir lateinit var tempDir: File

	private fun TestScope.startWatcher(
		root: File,
		batches: MutableList<ChangedFiles.Known>,
	): AndroidProjectWatcher {
		val watcher =
			AndroidProjectWatcher(
				watchedRoots = listOf(root),
				watchedFiles = emptyList(),
				filter = WatchFilter(listOf(root)),
				// backgroundScope so the never-ending poll job is cancelled with the test.
				scope = backgroundScope,
				// Park the automatic sweep; tests call sweep() deterministically.
				pollIntervalMillis = 3_600_000L,
				quietMillis = QUIET_MILLIS,
				maxMillis = MAX_MILLIS,
				pollDispatcher = StandardTestDispatcher(testScheduler),
			)
		watcher.start { batches += it }
		// Run the poll loop's initFingerprints() pass before any edit, so the fingerprint
		// state matches a long-running session's.
		runCurrent()
		return watcher
	}

	/** Advances past the quiet window and the cap, so every pending batch has been emitted. */
	private fun TestScope.settle() {
		advanceTimeBy(MAX_MILLIS + 1)
		runCurrent()
	}

	@Test
	fun `post-write mtime settle does not re-emit the same edit via the poll`() =
		runTest {
			val root = File(tempDir, "src").apply { mkdirs() }
			val manifest =
				File(root, "main/AndroidManifest.xml").apply {
					parentFile!!.mkdirs()
					writeText("<manifest v1/>")
				}
			val batches = mutableListOf<ChangedFiles.Known>()
			val watcher = startWatcher(root, batches)

			// The adb-push shape: write, inotify CLOSE_WRITE fingerprints current attrs,
			// then utimensat back-dates mtime with no further masked event.
			manifest.writeText("<manifest v2 -- edited/>")
			watcher.report(manifest, fromPoll = false)
			assertThat(manifest.setLastModified(manifest.lastModified() - 7_000)).isTrue()

			settle()
			assertThat(batches.single().files).containsExactly(manifest)

			// The poll sweep after the batch settled must stay quiet: the edit was already
			// delivered, only its attrs moved. A second batch here is a phantom, and costs a
			// double invalidation/rebaseline.
			watcher.sweep()
			settle()
			assertThat(batches).hasSize(1)
		}

	@Test
	fun `poll still catches a real change whose inotify events were dropped`() =
		runTest {
			val root = File(tempDir, "src").apply { mkdirs() }
			val source =
				File(root, "main/java/A.kt").apply {
					parentFile!!.mkdirs()
					writeText("class A")
				}
			val batches = mutableListOf<ChangedFiles.Known>()
			val watcher = startWatcher(root, batches)

			// A delivered edit settles as batch 1.
			source.writeText("class A { fun a() = 1 }")
			watcher.report(source, fromPoll = false)
			settle()
			assertThat(batches).hasSize(1)

			// A later REAL write with every inotify event dropped (sdcardfs): only the
			// poll can see it. The settle-time re-stamp must not have eaten this.
			source.writeText("class A { fun a() = 1; fun b() = 2 }")
			watcher.sweep()
			settle()
			assertThat(batches).hasSize(2)
			assertThat(batches[1].files).containsExactly(source)

			// And once delivered, a further sweep with no change stays quiet.
			watcher.sweep()
			settle()
			assertThat(batches).hasSize(2)
		}

	@Test
	fun `file deleted before the batch settles still reaches the pipeline once`() =
		runTest {
			val root = File(tempDir, "src").apply { mkdirs() }
			val source =
				File(root, "main/java/B.kt").apply {
					parentFile!!.mkdirs()
					writeText("class B")
				}
			val batches = mutableListOf<ChangedFiles.Known>()
			val watcher = startWatcher(root, batches)

			source.writeText("class B { }")
			watcher.report(source, fromPoll = false)
			// Gone before the quiet window elapses: the settle re-stamp must skip it, and
			// the poll's set-diff then emits the removal exactly once.
			assertThat(source.delete()).isTrue()
			settle()
			assertThat(batches.single().files).containsExactly(source)

			watcher.sweep()
			settle()
			assertThat(batches).hasSize(2)
			assertThat(batches[1].removed).containsExactly(source)

			watcher.sweep()
			settle()
			assertThat(batches).hasSize(2)
		}

	private companion object {
		private const val QUIET_MILLIS = 60L
		private const val MAX_MILLIS = 500L
	}
}
