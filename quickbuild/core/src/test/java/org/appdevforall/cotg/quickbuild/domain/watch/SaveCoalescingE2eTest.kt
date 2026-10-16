@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.domain.watch

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.AndroidProjectWatcher
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.reload.OrchestratorEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end coalescing test for the save-to-build path - real files, real watcher,
 * reconciler and orchestrator - pinning the BUILD COUNT a save pattern produces, which no
 * single-layer test can see: the watcher's quiet window plus cap collapses one save's write
 * burst, while the orchestrator folds everything arriving mid-build into one follow-up.
 * Virtual time throughout; [AndroidProjectWatcher.report] stands in for inert FileObserver.
 */
class SaveCoalescingE2eTest {
	@TempDir lateinit var tempDir: File

	/**
	 * Counts builds and records what each one read off disk when it started, which is how a
	 * dropped follow-up shows up as stale content rather than merely as a smaller count.
	 *
	 * @param buildMillis how long one build occupies the pipeline, in virtual time. Zero
	 *   finishes without suspending, so a save always finds the pipeline free.
	 */
	private class RecordingExecutor(
		private val buildMillis: Long,
	) : LiveReloadExecutor {
		val requests = mutableListOf<BuildRequest>()
		val contentSeen = mutableListOf<String>()
		private var generation = 0L

		override suspend fun execute(request: BuildRequest): BuildOutcome {
			requests += request
			contentSeen += readInputs(request.changes)
			if (buildMillis > 0) delay(buildMillis)
			return BuildOutcome.Success(generation = ++generation, durationMillis = buildMillis)
		}

		/** The build's inputs as the compiler would find them: read at start, path order. */
		private fun readInputs(changes: ChangedFiles): String =
			when (changes) {
				is ChangedFiles.Known -> {
					changes.files
						.sortedBy(File::getPath)
						.joinToString("|") { if (it.isFile) it.readText() else "<gone>" }
				}

				ChangedFiles.Unknown -> {
					"<unknown>"
				}
			}
	}

	/**
	 * The live pipeline under test plus the handles a test needs to drive it.
	 *
	 * @property src the watched source root; saves land under it.
	 */
	private class Harness(
		val src: File,
		private val watcher: AndroidProjectWatcher,
		val executor: RecordingExecutor,
		val events: MutableList<OrchestratorEvent>,
	) {
		/**
		 * One editor save of [name] with [text]: the write, then the MODIFY and CLOSE_WRITE
		 * inotify pair a single save actually produces. Collapsing that pair is the whole job
		 * of the quiet window, so a save that reported only once would test a weaker thing.
		 *
		 * @return the saved file, for asserting on the changed set.
		 */
		fun save(
			name: String,
			text: String,
		): File {
			val file =
				File(src, name).apply {
					parentFile!!.mkdirs()
					writeText(text)
				}
			watcher.report(file, fromPoll = false)
			watcher.report(file, fromPoll = false)
			return file
		}

		/** Drives one mtime sweep, the path that can turn one save into two builds. */
		fun poll() = watcher.sweep()

		val buildCount: Int get() = executor.requests.size

		/** The changed set of build [index], which is always enumerated on this path. */
		fun filesOf(index: Int): Set<File> = (executor.requests[index].changes as ChangedFiles.Known).files
	}

	/**
	 * Wires watcher -> reconciler -> orchestrator exactly as
	 * `QuickBuildSessionManager.onWatcherBatch` does, on the test scheduler's clock.
	 *
	 * @param buildMillis virtual duration of every build; leave at zero for a pipeline that is
	 *   always free, raise it above the save spacing to test in-flight coalescing.
	 */
	private fun TestScope.start(buildMillis: Long = 0L): Harness {
		val src = File(tempDir, "app/src/main").apply { mkdirs() }
		val executor = RecordingExecutor(buildMillis)
		val events = mutableListOf<OrchestratorEvent>()
		val orchestrator =
			LiveReloadOrchestrator(
				executor,
				ChangeClassifier(),
				backgroundScope,
				now = { testScheduler.currentTime },
			) { events += it }
		val watcher =
			AndroidProjectWatcher(
				watchedRoots = listOf(src),
				watchedFiles = emptyList(),
				filter = WatchFilter(listOf(src)),
				// backgroundScope so the never-ending poll job is cancelled with the test.
				scope = backgroundScope,
				// Park the automatic sweep; a test that wants one calls poll().
				pollIntervalMillis = PARKED_POLL_MILLIS,
				quietMillis = ChangeCoalescingDefaults.QUIET_MILLIS,
				maxMillis = ChangeCoalescingDefaults.MAX_MILLIS,
				pollDispatcher = StandardTestDispatcher(testScheduler),
			)
		watcher.start { batch ->
			val reconciled = WatcherBatchReconciler.reconcile(batch, File::isFile)
			if (!reconciled.isEmpty) backgroundScope.launch { orchestrator.onFilesChanged(reconciled) }
		}
		// Run the poll loop's initFingerprints() pass before any edit, so the fingerprint
		// state matches a long-running session's.
		runCurrent()
		return Harness(src, watcher, executor, events)
	}

	/** Advances just past the quiet window, so a settled burst has flushed and started its build. */
	private fun TestScope.flushBurst() {
		advanceTimeBy(ChangeCoalescingDefaults.QUIET_MILLIS + 1)
		runCurrent()
	}

	/** Advances past the cap as well, so nothing can still be accumulating anywhere. */
	private fun TestScope.settle() {
		advanceTimeBy(ChangeCoalescingDefaults.MAX_MILLIS + 1)
		runCurrent()
	}

	@Test
	fun `saves inside the quiet window are one build carrying the final content`() =
		runTest {
			val h = start()

			// Three saves of the same file, each well inside the quiet window - a fast typist
			// hitting save, or an editor's own save-then-format pair.
			h.save(SOURCE, "class A { fun a() = 1 }")
			advanceTimeBy(ChangeCoalescingDefaults.QUIET_MILLIS / 3)
			runCurrent()
			h.save(SOURCE, "class A { fun a() = 12 }")
			advanceTimeBy(ChangeCoalescingDefaults.QUIET_MILLIS / 3)
			runCurrent()
			val file = h.save(SOURCE, "class A { fun a() = 123 }")
			settle()

			assertThat(h.buildCount).isEqualTo(1)
			assertThat(h.filesOf(0)).containsExactly(file)
			// The one build compiled the LAST save, not the first: coalescing may drop a build,
			// never an edit.
			assertThat(h.executor.contentSeen).containsExactly("class A { fun a() = 123 }")
		}

	@Test
	fun `saves arriving during a build become one follow-up build, not one each`() =
		runTest {
			val h = start(buildMillis = LONG_BUILD_MILLIS)

			h.save(SOURCE, "class A")
			flushBurst()
			assertThat(h.buildCount).isEqualTo(1)

			// Three more saves, each its own settled batch (spaced beyond the quiet window, so
			// the watcher does NOT coalesce them) landing while build 1 is still running.
			listOf("java/B.kt" to "class B", "java/C.kt" to "class C", "java/D.kt" to "class D")
				.forEach { (name, text) ->
					h.save(name, text)
					flushBurst()
				}

			// Nothing queued behind the in-flight build: three batches, still one build.
			assertThat(h.buildCount).isEqualTo(1)

			advanceTimeBy(LONG_BUILD_MILLIS)
			runCurrent()

			// Exactly one follow-up, carrying all three files at once.
			assertThat(h.buildCount).isEqualTo(2)
			assertThat(h.filesOf(1))
				.containsExactly(
					File(h.src, "java/B.kt"),
					File(h.src, "java/C.kt"),
					File(h.src, "java/D.kt"),
				)

			// And no third build behind that one.
			advanceTimeBy(LONG_BUILD_MILLIS + ChangeCoalescingDefaults.MAX_MILLIS)
			runCurrent()
			assertThat(h.buildCount).isEqualTo(2)
		}

	@Test
	fun `the newest save wins when several land during one build`() =
		runTest {
			val h = start(buildMillis = LONG_BUILD_MILLIS)

			h.save(SOURCE, "v1")
			flushBurst()
			assertThat(h.buildCount).isEqualTo(1)

			// Bryan's pattern, but faster than a build: delete a character, save, repeat. Each
			// save is its own watcher batch; all of them coalesce into one follow-up.
			h.save(SOURCE, "v2")
			flushBurst()
			h.save(SOURCE, "v3")
			flushBurst()

			advanceTimeBy(LONG_BUILD_MILLIS)
			runCurrent()

			// The follow-up must exist AND must have compiled v3. A coalescer that dropped the
			// follow-up would leave the phone running v1 with the user looking at v3.
			assertThat(h.buildCount).isEqualTo(2)
			assertThat(h.executor.contentSeen).containsExactly("v1", "v3").inOrder()
		}

	@Test
	fun `saves spaced beyond the quiet window each get their own build`() =
		runTest {
			// The negative case, and Bryan's manual-QA pattern exactly: a character deleted and
			// saved every few hundred ms, with each build finishing before the next save. Four
			// builds is correct - the quiet window collapses one save's writes, and is not a
			// throttle on a user who keeps asking.
			val h = start()
			val spacing = ChangeCoalescingDefaults.QUIET_MILLIS * 4

			repeat(4) { i ->
				h.save(SOURCE, "v$i")
				advanceTimeBy(spacing)
				runCurrent()
			}
			settle()

			assertThat(h.buildCount).isEqualTo(4)
			assertThat(h.executor.contentSeen).containsExactly("v0", "v1", "v2", "v3").inOrder()
		}

	@Test
	fun `a continuous write stream builds on the cap, not per write`() =
		runTest {
			// Codegen or a git checkout writing without a gap: the quiet timer keeps resetting,
			// so only the cap can flush. Far fewer builds than writes, and the last write is
			// still compiled.
			val h = start()
			val step = ChangeCoalescingDefaults.QUIET_MILLIS / 2
			val writes = (ChangeCoalescingDefaults.MAX_MILLIS * 2 / step).toInt()

			repeat(writes) { i ->
				h.save(SOURCE, "w$i")
				advanceTimeBy(step)
				runCurrent()
			}
			settle()

			// 26 writes, two builds: the cap flushes the first batch at MAX_MILLIS, and the
			// second flushes on the quiet window once the stream stops - before its own cap.
			assertThat(writes).isEqualTo(26)
			assertThat(h.buildCount).isEqualTo(2)
			assertThat(h.executor.contentSeen.last()).isEqualTo("w${writes - 1}")
		}

	@Test
	fun `a poll sweep after a settled save adds no second build`() =
		runTest {
			// The phantom-double-build shape: inotify delivered the save, then the 2s mtime
			// sweep saw a stamp it had not restamped and re-emitted the same edit.
			val h = start()

			h.save(SOURCE, "class A { fun a() = 1 }")
			settle()
			assertThat(h.buildCount).isEqualTo(1)

			h.poll()
			settle()
			assertThat(h.buildCount).isEqualTo(1)
		}

	@Test
	fun `the debounce window these cases are driven off is the production one`() {
		// The cases above take the window from these constants, so they follow a retune rather
		// than failing on it. This is the one place a retune is a deliberate decision: 150 ms is
		// short enough that a save still feels immediate, and the 1 s cap keeps a continuous
		// write stream from deferring a build indefinitely.
		assertThat(ChangeCoalescingDefaults.QUIET_MILLIS).isEqualTo(150L)
		assertThat(ChangeCoalescingDefaults.MAX_MILLIS).isEqualTo(1_000L)
	}

	private companion object {
		private const val SOURCE = "java/A.kt"

		/** Long enough that every save in an in-flight test lands before the build ends. */
		private const val LONG_BUILD_MILLIS = 5_000L

		/** An hour: the automatic sweep never fires, so tests drive poll() themselves. */
		private const val PARKED_POLL_MILLIS = 3_600_000L
	}
}
