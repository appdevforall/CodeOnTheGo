package com.itsaky.androidide.utils

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import com.itsaky.androidide.flashbar.R as FlashbarR

/**
 * Whether an indefinite error bar counts as dismissed: by the Dismiss button yes, by its activity
 * going away no. Destroying the activity only detaches the bar's view, and Flashbar fires no dismiss
 * callback for that, which is what lets a caller keep a report unread when a font scale change
 * recreates the screen. With the touch-dismiss experiment on, a tap on the bar dismisses it too, but
 * is reported as not the Dismiss button, so a caller does not count it as read.
 */
@RunWith(RobolectricTestRunner::class)
class TrackedFlashbarTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var originalResolver: (String) -> File

	@Before
	fun setUp() {
		originalResolver = FeatureFlags.flagFileResolver
		FeatureFlags.resetForTest()
		FeatureFlags.flagFileResolver = { name -> File(tempFolder.root, name) }
	}

	@After
	fun tearDown() {
		FeatureFlags.flagFileResolver = originalResolver
		FeatureFlags.resetForTest()
	}

	private fun experiments(on: Boolean) {
		if (on) tempFolder.newFile("CodeOnTheGo.exp")
		runBlocking { FeatureFlags.initialize() }
		assertThat(Experiments.isOn(Experiment.ERROR_BAR_TOUCH_DISMISS)).isEqualTo(on)
	}

	class ThemedActivity : Activity() {
		override fun onCreate(savedInstanceState: Bundle?) {
			setTheme(R.style.Theme_AndroidIDE)
			super.onCreate(savedInstanceState)
		}
	}

	private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

	private fun TrackedFlashbar.isShown() = runBlocking { awaitShown() }

	/** null while the bar is up; once dismissed, whether the Dismiss button did it. */
	private fun TrackedFlashbar.dismissal(): Boolean? = runBlocking { withTimeoutOrNull(1) { awaitDismissed() } }

	private fun TrackedFlashbar.isDismissed() = dismissal() != null

	private fun Activity.tap(id: Int) {
		window.decorView.findViewById<View>(id).performClick()
		idle()
	}

	@Test
	fun `the Dismiss button dismisses the bar`() {
		experiments(on = false)
		val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
		val bar = controller.get().flashErrorTracked("boom")!!
		idle()
		assertThat(bar.isShown()).isTrue()
		assertThat(bar.isDismissed()).isFalse()

		controller.get().tap(FlashbarR.id.fbPositiveAction)

		assertThat(bar.dismissal()).isTrue()
	}

	@Test
	fun `with touch dismiss on, the Dismiss button still counts as the Dismiss button`() {
		experiments(on = true)
		val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
		val bar = controller.get().flashErrorTracked("boom")!!
		idle()

		controller.get().tap(FlashbarR.id.fbPositiveAction)

		assertThat(bar.dismissal()).isTrue()
	}

	@Test
	fun `with touch dismiss on, a tap on the bar dismisses it but not as the Dismiss button`() {
		experiments(on = true)
		val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
		val bar = controller.get().flashErrorTracked("boom")!!
		idle()
		assertThat(bar.isShown()).isTrue()

		controller.get().tap(FlashbarR.id.fbRoot)

		assertWithMessage("a tap on the bar should still close it").that(bar.isDismissed()).isTrue()
		assertWithMessage("a tap on the bar counted as the Dismiss button").that(bar.dismissal()).isFalse()
	}

	@Test
	fun `with touch dismiss off, a tap on the bar does not dismiss it`() {
		experiments(on = false)
		val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
		val bar = controller.get().flashErrorTracked("boom")!!
		idle()

		controller.get().tap(FlashbarR.id.fbRoot)

		assertThat(bar.isDismissed()).isFalse()
	}

	@Test
	fun `a font scale change that recreates the activity does not dismiss the bar`() {
		val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
		val first = controller.get()
		val bar = first.flashErrorTracked("boom")!!
		idle()
		assertThat(bar.isShown()).isTrue()

		controller.configurationChange(Configuration(first.resources.configuration).apply { fontScale = 2f })
		idle()

		assertThat(controller.get()).isNotSameInstanceAs(first)
		assertThat(first.isDestroyed).isTrue()
		assertThat(bar.isDismissed()).isFalse()
	}

	@Test
	fun `finishing and destroying the activity does not dismiss the bar`() {
		val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
		val bar = controller.get().flashErrorTracked("boom")!!
		idle()
		assertThat(bar.isShown()).isTrue()

		controller.get().finish()
		controller.pause().stop().destroy()
		idle()

		assertThat(bar.isDismissed()).isFalse()
	}
}
