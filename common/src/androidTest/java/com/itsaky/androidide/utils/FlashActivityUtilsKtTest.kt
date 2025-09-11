package com.itsaky.androidide.utils

import android.app.Activity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsaky.androidide.testing.android.TestMaterialActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Akash Yadav
 */
@RunWith(AndroidJUnit4::class)
class FlashActivityUtilsKtTest {
	@get:Rule
	val scenarioRule = ActivityScenarioRule(TestMaterialActivity::class.java)

	private fun Activity.createFlashbar(title: String = "Some title") = flashbarBuilder().title(title)

	@Test
	fun showFlashbarFromUiThread_doesNotCrash() {
		scenarioRule.scenario.onActivity { activity ->
			// test showing from a UI thread
			activity.createFlashbar("showFlashbarFromUiThread_doesNotCrash").showOnUiThread()
		}
	}

	@Test
	fun showFlashbarFromBackgroundThread_doesNotCrash() {
		scenarioRule.scenario.onActivity { activity ->
			val latch = CountDownLatch(1)

			Thread {
				try {
					// test showing from a background thread
					activity
						.createFlashbar("showFlashbarFromBackgroundThread_doesNotCrash")
						.showOnUiThread()
				} catch (e: Exception) {
					throw AssertionError("Exception thrown from background thread: ${e.message}", e)
				} finally {
					latch.countDown()
				}
			}.start()

			// Wait for thread to complete or timeout
			assertTrue(latch.await(5, TimeUnit.SECONDS))
		}
	}
}
