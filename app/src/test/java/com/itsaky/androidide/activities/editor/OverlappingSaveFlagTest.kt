/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.activities.editor

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Overlapping saves must never leave `areFilesSaving` false while a save is still writing -
 * SaveFileAction re-enables itself off that flag, so a false gap lets the user fire a second
 * save into an in-flight write.
 *
 * The interleaving is forced, not timed. Robolectric's paused main looper holds the off-main
 * save's completion hop in the queue while the main-thread save runs its own hop inline
 * (`Main.immediate` skips the queue when already on main) - exactly the ordering inversion at
 * issue.
 *
 *  - BUGGED: the count moves off-main, so the finishing off-main save decrements to 0 and
 *    queues `false`; the main-thread save then sees 0 -> 1 and writes `true` inline; the
 *    queued `false` runs last -> flag false mid-save -> test FAILS.
 *  - FIXED: count and flag move together on main, so the queued decrement lands as 2 -> 1 and
 *    writes nothing -> flag stays true -> test PASSES.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OverlappingSaveFlagTest.TestApp::class)
class OverlappingSaveFlagTest {
	open class TestApp : BaseApplication()

	@Test
	fun givenOverlappingSaves_whenTheOffMainSaveCompletionDrains_thenTheSavingFlagStaysRaised() {
		val activity = Robolectric.buildActivity(EditorHandlerActivity::class.java).get()
		val mainLooper = shadowOf(Looper.getMainLooper())

		// Save A begins on the main thread: the hop runs inline, raising the flag.
		runBlocking { activity.beginFileSave() }
		assertThat(activity.editorViewModel.areFilesSaving).isTrue()

		// Save A finishes off-main. Its completion hop is posted to the paused main looper and
		// parks there; the worker stays blocked until we idle the looper.
		val ended = CountDownLatch(1)
		val worker =
			thread(isDaemon = true) {
				runBlocking(Dispatchers.IO) { activity.endFileSave() }
				ended.countDown()
			}

		try {
			awaitPostToMain(mainLooper)

			// Save B begins on the main thread while A's hop is still queued.
			runBlocking { activity.beginFileSave() }

			// Drain A's queued completion. B is still writing, so the flag must stay raised.
			mainLooper.idle()
			assertThat(ended.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()
			assertThat(activity.editorViewModel.areFilesSaving).isTrue()

			// Only B finishing lowers it.
			runBlocking { activity.endFileSave() }
			assertThat(activity.editorViewModel.areFilesSaving).isFalse()
		} finally {
			// A failed assertion above can leave the worker parked on a main-thread hop that
			// never runs; drain the queue and reap it rather than leak a blocked thread.
			mainLooper.idle()
			worker.join(TIMEOUT_MS)
		}
	}

	/** Blocks until the worker's main-thread hop is sitting in the paused looper's queue. */
	private fun awaitPostToMain(mainLooper: ShadowLooper) {
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		while (mainLooper.isIdle && System.currentTimeMillis() < deadline) {
			Thread.sleep(1)
		}
		assertThat(mainLooper.isIdle).isFalse()
	}

	private companion object {
		const val TIMEOUT_MS = 10_000L
	}
}
