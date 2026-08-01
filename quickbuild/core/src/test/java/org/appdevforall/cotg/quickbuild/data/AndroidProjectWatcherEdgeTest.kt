package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lifecycle and choke-point edges of [AndroidProjectWatcher] beyond
 * [AndroidProjectWatcherTest]'s pipeline cases, driven through the same JVM seams
 * ([AndroidProjectWatcher.report] / [AndroidProjectWatcher.sweep]).
 */
class AndroidProjectWatcherEdgeTest {
	@TempDir lateinit var tempDir: File

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private var watcher: AndroidProjectWatcher? = null

	@AfterEach
	fun tearDown() {
		watcher?.stop()
		scope.cancel()
	}

	private fun startWatcher(root: File): Pair<AndroidProjectWatcher, CopyOnWriteArrayList<ChangedFiles.Known>> {
		val batches = CopyOnWriteArrayList<ChangedFiles.Known>()
		val w =
			AndroidProjectWatcher(
				watchedRoots = listOf(root),
				watchedFiles = emptyList(),
				filter = WatchFilter(listOf(root)),
				scope = scope,
				pollIntervalMillis = 3_600_000, // parked; sweeps are driven manually
				quietMillis = 50,
				maxMillis = 1_000,
			)
		watcher = w
		w.start(batches::add)
		return w to batches
	}

	private fun awaitBatchCount(
		batches: List<ChangedFiles.Known>,
		count: Int,
	) {
		val deadline = System.currentTimeMillis() + 5_000
		while (batches.size < count && System.currentTimeMillis() < deadline) {
			Thread.sleep(10)
		}
		assertThat(batches.size).isAtLeast(count)
	}

	@Test
	fun `stop before start is a safe no-op`() {
		val w =
			AndroidProjectWatcher(
				watchedRoots = listOf(tempDir),
				watchedFiles = emptyList(),
				filter = WatchFilter(listOf(tempDir)),
				scope = scope,
			)

		// Nothing was started; stop must not throw on the never-armed jobs.
		w.stop()
	}

	@Test
	fun `a directory event is dropped at the choke point - never a compile input`() {
		val root = File(tempDir, "proj").apply { mkdirs() }
		val srcDir = File(root, "app/src/main/java/com/example").apply { mkdirs() }
		val source = File(srcDir, "Foo.kt").apply { writeText("class Foo") }
		val (w, batches) = startWatcher(root)

		// A directory "change" (as inotify would deliver for a mkdir) must not emit...
		w.report(srcDir, fromPoll = false)
		// ...while a real file change right after emits normally.
		w.report(source, fromPoll = false)
		awaitBatchCount(batches, 1)

		assertThat(batches[0].files).containsExactly(source)
	}

	@Test
	fun `the automatic poll loop sweeps a change to a batch without a manual sweep`() {
		val root = File(tempDir, "proj").apply { mkdirs() }
		val source =
			File(root, "app/src/main/java/com/example/Foo.kt").apply {
				parentFile!!.mkdirs()
				writeText("class Foo")
			}
		val batches = CopyOnWriteArrayList<ChangedFiles.Known>()
		val w =
			AndroidProjectWatcher(
				watchedRoots = listOf(root),
				watchedFiles = emptyList(),
				filter = WatchFilter(listOf(root)),
				scope = scope,
				pollIntervalMillis = 50, // real, running loop - this is the case under test
				quietMillis = 50,
				maxMillis = 1_000,
			)
		watcher = w
		w.start(batches::add)
		Thread.sleep(300) // let initFingerprints prime the baseline

		// A content change the (inert) inotify path never reports: only the poll's own
		// recurring sweep can deliver it.
		source.writeText("class Foo { val added = 1 }")
		awaitBatchCount(batches, 1)

		assertThat(batches[0].files).containsExactly(source)
	}

	@Test
	fun `a poll observation of an unchanged file stays quiet`() {
		val root = File(tempDir, "proj").apply { mkdirs() }
		val (w, batches) = startWatcher(root)
		// Let the poll loop's baseline priming finish, then create the file so it is a
		// genuinely NEW path from the poll's perspective.
		Thread.sleep(300)
		val source =
			File(root, "app/src/main/java/com/example/Foo.kt").apply {
				parentFile!!.mkdirs()
				writeText("class Foo")
			}

		// First poll sighting of the new path records the fingerprint and emits...
		w.report(source, fromPoll = true)
		awaitBatchCount(batches, 1)
		// ...but a second sweep over the untouched file must NOT re-emit (the
		// fingerprint gate is what keeps the hybrid from double-building).
		w.report(source, fromPoll = true)
		Thread.sleep(200)

		assertThat(batches).hasSize(1)
	}
}
