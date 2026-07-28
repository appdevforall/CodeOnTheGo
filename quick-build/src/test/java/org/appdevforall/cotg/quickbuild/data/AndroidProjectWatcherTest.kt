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
 * JVM tests for the watcher's poll/coalesce pipeline. FileObserver is inert on the JVM
 * (android.jar stubs + returnDefaultValues), so inotify deliveries are simulated by
 * calling [AndroidProjectWatcher.report] directly and poll sweeps are driven manually
 * via [AndroidProjectWatcher.sweep] (the automatic loop is parked with a huge interval).
 *
 * The regression under test (task #66): one `adb push` of a manifest produced TWO
 * invalidations/rebaselines. `adb push` back-dates the file's mtime (utimensat) right
 * after the CLOSE_WRITE the inotify path fingerprinted [measured on a56], so the next
 * 2s poll sweep saw a stamp "change" for content the first batch already delivered and
 * re-emitted it as a phantom second batch.
 */
class AndroidProjectWatcherTest {
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
				// Park the automatic sweep; tests call sweep() deterministically.
				pollIntervalMillis = 3_600_000L,
				quietMillis = 60L,
				maxMillis = 500L,
			)
		watcher = w
		w.start { batches += it }
		// Let the poll loop's seedFingerprints() pass complete before any edit, so the
		// fingerprint state matches a long-running session's.
		Thread.sleep(750)
		return w to batches
	}

	private fun awaitBatchCount(
		batches: List<ChangedFiles.Known>,
		count: Int,
		timeoutMillis: Long = 5_000,
	) {
		val deadline = System.currentTimeMillis() + timeoutMillis
		while (batches.size < count && System.currentTimeMillis() < deadline) {
			Thread.sleep(20)
		}
		assertThat(batches.size).isAtLeast(count)
	}

	@Test
	fun `post-write mtime settle does not re-emit the same edit via the poll`() {
		val root = File(tempDir, "src").apply { mkdirs() }
		val manifest =
			File(root, "main/AndroidManifest.xml").apply {
				parentFile!!.mkdirs()
				writeText("<manifest v1/>")
			}
		val (w, batches) = startWatcher(root)

		// The adb-push shape: write, inotify CLOSE_WRITE fingerprints current attrs,
		// then utimensat back-dates mtime with no further masked event.
		manifest.writeText("<manifest v2 -- edited/>")
		w.report(manifest, fromPoll = false)
		assertThat(manifest.setLastModified(manifest.lastModified() - 7_000)).isTrue()

		awaitBatchCount(batches, 1)
		assertThat(batches[0].files).containsExactly(manifest)

		// The poll sweep after the batch settled must stay quiet: the edit was already
		// delivered, only its attrs moved. Pre-fix this emitted a phantom second batch
		// (the double invalidation/rebaseline of task #66).
		w.sweep()
		Thread.sleep(300)
		assertThat(batches).hasSize(1)
	}

	@Test
	fun `poll still catches a real change whose inotify events were dropped`() {
		val root = File(tempDir, "src").apply { mkdirs() }
		val source =
			File(root, "main/java/A.kt").apply {
				parentFile!!.mkdirs()
				writeText("class A")
			}
		val (w, batches) = startWatcher(root)

		// A delivered edit settles as batch 1.
		source.writeText("class A { fun a() = 1 }")
		w.report(source, fromPoll = false)
		awaitBatchCount(batches, 1)

		// A later REAL write with every inotify event dropped (sdcardfs): only the
		// poll can see it. The settle-time re-stamp must not have eaten this.
		source.writeText("class A { fun a() = 1; fun b() = 2 }")
		w.sweep()
		awaitBatchCount(batches, 2)
		assertThat(batches[1].files).containsExactly(source)

		// And once delivered, a further sweep with no change stays quiet.
		w.sweep()
		Thread.sleep(300)
		assertThat(batches).hasSize(2)
	}

	@Test
	fun `file deleted before the batch settles still reaches the pipeline once`() {
		val root = File(tempDir, "src").apply { mkdirs() }
		val source =
			File(root, "main/java/B.kt").apply {
				parentFile!!.mkdirs()
				writeText("class B")
			}
		val (w, batches) = startWatcher(root)

		source.writeText("class B { }")
		w.report(source, fromPoll = false)
		// Gone before the quiet window elapses: the settle re-stamp must skip it, and
		// the poll's set-diff then emits the removal exactly once.
		assertThat(source.delete()).isTrue()
		awaitBatchCount(batches, 1)
		assertThat(batches[0].files).containsExactly(source)

		w.sweep()
		awaitBatchCount(batches, 2)
		assertThat(batches[1].removed).containsExactly(source)

		w.sweep()
		Thread.sleep(300)
		assertThat(batches).hasSize(2)
	}
}
