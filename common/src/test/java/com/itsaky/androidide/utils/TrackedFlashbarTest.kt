package com.itsaky.androidide.utils

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import com.itsaky.androidide.flashbar.R as FlashbarR

/**
 * Whether an indefinite error bar counts as dismissed: by the Dismiss button yes, by its activity
 * going away no. Destroying the activity only detaches the bar's view, and Flashbar fires no dismiss
 * callback for that, which is what lets a caller keep a report unread when a font scale change
 * recreates the screen (F30).
 */
@RunWith(RobolectricTestRunner::class)
class TrackedFlashbarTest {
	class ThemedActivity : Activity() {
		override fun onCreate(savedInstanceState: Bundle?) {
			setTheme(R.style.Theme_AndroidIDE)
			super.onCreate(savedInstanceState)
		}
	}

	private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

	private fun TrackedFlashbar.isShown() = runBlocking { awaitShown() }

	private fun TrackedFlashbar.isDismissed() = runBlocking { withTimeoutOrNull(1) { awaitDismissed() } } != null

	@Test
	fun `the Dismiss button dismisses the bar`() {
		val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
		val bar = controller.get().flashErrorTracked("boom")!!
		idle()
		assertThat(bar.isShown()).isTrue()
		assertThat(bar.isDismissed()).isFalse()

		controller
			.get()
			.window.decorView
			.findViewById<View>(FlashbarR.id.fbPositiveAction)
			.performClick()
		idle()

		assertThat(bar.isDismissed()).isTrue()
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
