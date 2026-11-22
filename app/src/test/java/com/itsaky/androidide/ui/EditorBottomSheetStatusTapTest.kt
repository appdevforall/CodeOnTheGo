package com.itsaky.androidide.ui

import android.view.Gravity
import androidx.fragment.app.FragmentActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.app.BaseApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status-line tap is wired into [EditorBottomSheet] itself (d1f8094e6): [setStatus] hands every
 * write to the tap binding, and [setStatusTap] binds through it. [StatusLineTapBindingTest] covers
 * the binding alone, so these go through the sheet's public API and fail if either call is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EditorBottomSheetStatusTapTest.TestApp::class)
class EditorBottomSheetStatusTapTest {
	open class TestApp : BaseApplication()

	private val activity =
		Robolectric
			.buildActivity(FragmentActivity::class.java)
			.also { it.get().setTheme(R.style.Theme_AndroidIDE) }
			.setup()
			.get()

	private val sheet = EditorBottomSheet(activity)
	private val statusText get() = sheet.binding.buildStatus.statusText

	private var taps = 0

	private fun bindTap(text: String) {
		sheet.setStatus(text, Gravity.START)
		sheet.setStatusTap("show details") { taps++ }
	}

	@Test
	fun `binding a tap through the sheet makes the status line clickable`() {
		bindTap("Quick Build: off - tap for details")

		assertThat(statusText.isClickable).isTrue()
		statusText.performClick()
		assertThat(taps).isEqualTo(1)
	}

	@Test
	fun `other text written through setStatus drops the tap`() {
		bindTap("Quick Build: off - tap for details")

		sheet.setStatus("Indexing project", Gravity.START)

		assertThat(statusText.isClickable).isFalse()
		assertThat(statusText.hasOnClickListeners()).isFalse()
	}

	@Test
	fun `the same text written again through setStatus keeps the tap`() {
		bindTap("Quick Build: off - tap for details")

		sheet.setStatus("Quick Build: off - tap for details", Gravity.START)

		assertThat(statusText.isClickable).isTrue()
		statusText.performClick()
		assertThat(taps).isEqualTo(1)
	}
}
