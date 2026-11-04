package com.itsaky.androidide.git.core

import android.os.FileObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class GitRepositoryWatcherTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun testStartAndStopLifecycle() =
		runTest {
			val gitDir = tempFolder.newFolder(".git")
			var callbackCount = 0
			val watcher =
				GitRepositoryWatcher(
					gitDir = gitDir,
					scope = backgroundScope,
					debounceDelay = 1000.milliseconds,
					context = coroutineContext,
					onGitStateChanged = { callbackCount++ },
				)

			assertFalse(watcher.isRunning)
			watcher.start()
			assertTrue(watcher.isRunning)

			watcher.stop()
			assertFalse(watcher.isRunning)
		}

	@Test
	fun testDebounceCoalescesBurstEvents() =
		runTest {
			val gitDir = tempFolder.newFolder(".git")
			var callbackCount = 0
			val watcher =
				GitRepositoryWatcher(
					gitDir = gitDir,
					scope = backgroundScope,
					debounceDelay = 1000.milliseconds,
					context = coroutineContext,
					onGitStateChanged = { callbackCount++ },
				)

			watcher.start()

			// First event at t = 0ms
			watcher.handleFileEvent(FileObserver.MODIFY, "index")
			advanceTimeBy(400)
			assertEquals(0, callbackCount)

			// Second event at t = 400ms resets the 1000ms debounce
			watcher.handleFileEvent(FileObserver.CLOSE_WRITE, "index")
			advanceTimeBy(700) // t = 1100ms total, but only 700ms since last event
			assertEquals(0, callbackCount)

			// Third event at t = 1100ms resets again
			watcher.handleFileEvent(FileObserver.MOVED_TO, "HEAD")
			advanceTimeBy(999) // 999ms since last event
			assertEquals(0, callbackCount)

			// Step past the 1000ms quiet period from last event
			advanceTimeBy(2) // 1001ms since last event
			assertEquals(1, callbackCount)

			watcher.stop()
		}

	@Test
	fun testPauseAndResumeSuppressesEvents() =
		runTest {
			val gitDir = tempFolder.newFolder(".git")
			var callbackCount = 0
			val watcher =
				GitRepositoryWatcher(
					gitDir = gitDir,
					scope = backgroundScope,
					debounceDelay = 1000.milliseconds,
					context = coroutineContext,
					onGitStateChanged = { callbackCount++ },
				)

			watcher.start()
			watcher.pause()
			assertTrue(watcher.isPaused)

			// Event while paused should be dropped
			watcher.handleFileEvent(FileObserver.MODIFY, "index")
			advanceTimeBy(2000)
			assertEquals(0, callbackCount)

			// Resume and trigger event
			watcher.resume()
			assertFalse(watcher.isPaused)

			watcher.handleFileEvent(FileObserver.MODIFY, "index")
			advanceTimeBy(1001)
			assertEquals(1, callbackCount)

			watcher.stop()
		}

	@Test
	fun testStopCancelsPendingDebouncedExecution() =
		runTest {
			val gitDir = tempFolder.newFolder(".git")
			var callbackCount = 0
			val watcher =
				GitRepositoryWatcher(
					gitDir = gitDir,
					scope = backgroundScope,
					debounceDelay = 1000.milliseconds,
					context = coroutineContext,
					onGitStateChanged = { callbackCount++ },
				)

			watcher.start()
			watcher.handleFileEvent(FileObserver.MODIFY, "index")
			advanceTimeBy(500)

			// Stopping cancels the debounced job
			watcher.stop()
			advanceTimeBy(1000)
			assertEquals(0, callbackCount)
		}

	@Test
	fun testIgnoresEventsWhenNotRunning() =
		runTest {
			val gitDir = tempFolder.newFolder(".git")
			var callbackCount = 0
			val watcher =
				GitRepositoryWatcher(
					gitDir = gitDir,
					scope = backgroundScope,
					debounceDelay = 1000.milliseconds,
					context = coroutineContext,
					onGitStateChanged = { callbackCount++ },
				)

			// Not started
			watcher.handleFileEvent(FileObserver.MODIFY, "index")
			advanceTimeBy(2000)
			assertEquals(0, callbackCount)
		}

	@Test
	fun testIgnoresIrrelevantEventsAndNullPaths() =
		runTest {
			val gitDir = tempFolder.newFolder(".git")
			var callbackCount = 0
			val watcher =
				GitRepositoryWatcher(
					gitDir = gitDir,
					scope = backgroundScope,
					debounceDelay = 1000.milliseconds,
					context = coroutineContext,
					onGitStateChanged = { callbackCount++ },
				)

			watcher.start()

			// Null path
			watcher.handleFileEvent(FileObserver.MODIFY, null)
			advanceTimeBy(1500)
			assertEquals(0, callbackCount)

			// ACCESS event (read) is not in EVENT_MASK
			watcher.handleFileEvent(FileObserver.ACCESS, "index")
			advanceTimeBy(1500)
			assertEquals(0, callbackCount)

			watcher.stop()
		}

	@Test
	fun testCreateWatcherFactoryOnGitRepository() =
		runTest {
			val repoDir = tempFolder.newFolder("repo")
			val gitDir = File(repoDir, ".git").apply { mkdirs() }

			var callbackCount = 0
			val watcher =
				GitRepositoryWatcher(
					gitDir = gitDir,
					scope = backgroundScope,
					debounceDelay = 1000.milliseconds,
					context = coroutineContext,
					onGitStateChanged = { callbackCount++ },
				)

			assertEquals(gitDir, watcher.gitDir)
			assertEquals(1000.milliseconds, watcher.debounceDelay)
		}
}
