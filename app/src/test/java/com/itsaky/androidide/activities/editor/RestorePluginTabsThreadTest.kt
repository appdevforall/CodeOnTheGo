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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADFA-4331 repro: [EditorHandlerActivity.restoreOpenedPluginTabs] must do its
 * SharedPreferences IO + Gson decode OFF the main thread (it stalls startup otherwise).
 *
 * Detector (deterministic, no timing): we run the real, private production method with
 * the Main dispatcher pinned to a single-threaded [StandardTestDispatcher] (so "main" ==
 * the test thread). The method, when it finishes restoring, clears the cached-tabs
 * preference via `prefManager.putString(KEY, null)`, which posts a [PreferenceChangeEvent]
 * on EventBus on the *thread that executed the write*. We capture that thread.
 *
 *  - BUGGED (stage): the whole body — pref read, Gson decode, final pref write — runs
 *    directly inside `lifecycleScope.launch { }` on the Main dispatcher, i.e. the test
 *    thread. The captured write thread == the test/main thread  ->  test FAILS.
 *  - FIXED (branch): read/decode are wrapped in `withContext(Dispatchers.IO/Default)` and
 *    the final write in `withContext(Dispatchers.IO)`, so the write runs on a real
 *    background thread != the test/main thread  ->  test PASSES.
 *
 * UI tab-selection is skipped: we pre-seed the private `pluginTabIndices` map with the
 * decoded id so `restoreOpenedPluginTabs` short-circuits the `selectPluginTabById(...)`
 * UI call (`if (!pluginTabIndices.containsKey(tabId))`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = RestorePluginTabsThreadTest.TestApp::class)
class RestorePluginTabsThreadTest {

  open class TestApp : BaseApplication()

  /** Captures the thread on which the cached-tabs preference write executed. */
  class WriteThreadCapture {
    @Volatile var writeThreadName: String? = null
    val latch = CountDownLatch(1)

    @Subscribe(threadMode = ThreadMode.POSTING)
    fun onPrefChange(event: PreferenceChangeEvent) {
      if (event.key == EditorHandlerActivity.PREF_KEY_OPEN_PLUGIN_TABS && event.value == null) {
        writeThreadName = Thread.currentThread().name
        latch.countDown()
      }
    }
  }

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** Asserts the cached-tabs clearing write runs on a background thread, not the main thread. */
  @Test
  fun `restore decodes and writes off the main thread`() {
    val mainThreadName = Thread.currentThread().name

    val controller = Robolectric.buildActivity(EditorHandlerActivity::class.java)
    val activity = controller.get()
    val app = activity.application as BaseApplication

    // Seed the cached plugin-tabs JSON that the method will read + decode.
    app.prefManager.putString(
      EditorHandlerActivity.PREF_KEY_OPEN_PLUGIN_TABS,
      """["tabA"]""",
    )

    // Pre-seed the private pluginTabIndices map so restoreOpenedPluginTabs() skips the
    // UI-touching selectPluginTabById("tabA") call.
    val mapField = EditorHandlerActivity::class.java.getDeclaredField("pluginTabIndices")
    mapField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val pluginTabIndices = mapField.get(activity) as MutableMap<String, Int>
    pluginTabIndices["tabA"] = 0

    // Subscribe AFTER seeding so we only capture the method's own clearing write.
    val capture = WriteThreadCapture()
    EventBus.getDefault().register(capture)
    try {
      // Invoke the real private production method.
      val method = EditorHandlerActivity::class.java.getDeclaredMethod("restoreOpenedPluginTabs")
      method.isAccessible = true
      method.invoke(activity)

      // The launched coroutine hops between the (test) Main dispatcher and the real
      // Dispatchers.IO/Default background pools. Pump the test scheduler repeatedly while
      // giving the background hops time to complete, until the clearing write fires.
      val deadline = System.currentTimeMillis() + 10_000
      while (capture.latch.count > 0 && System.currentTimeMillis() < deadline) {
        testDispatcher.scheduler.advanceUntilIdle()
        capture.latch.await(50, TimeUnit.MILLISECONDS)
      }

      val writeThread = capture.writeThreadName
      assertThat(writeThread).isNotNull()
      // The fix requires the IO/decode work to run OFF the main thread.
      assertThat(writeThread).isNotEqualTo(mainThreadName)
    } finally {
      EventBus.getDefault().unregister(capture)
    }
  }
}
