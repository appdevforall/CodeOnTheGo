package com.itsaky.androidide.ui

import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.FragmentActivity
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.app.BaseApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
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

	private lateinit var controller: ActivityController<FragmentActivity>
	private lateinit var sheet: EditorBottomSheet
	private var savedHandler: Thread.UncaughtExceptionHandler? = null
	private val uncaught = mutableListOf<String>()

	@Before
	fun setUp() {
		// IDEApplication.onCreate chains the previous default handler into its own, so once a JVM has
		// created two of them, an uncaught exception loops between the handlers until the heap runs
		// out (ADFA-7259). Catch uncaught exceptions here instead. The sheet's collectors run on the
		// main thread, so only an exception there fails the test; one from a thread another test
		// left running is printed.
		savedHandler = Thread.getDefaultUncaughtExceptionHandler()
		val main = Looper.getMainLooper().thread
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			if (thread === main) {
				uncaught += "${thread.name}: $error"
			} else {
				System.err.println("Uncaught on ${thread.name} during EditorBottomSheetStatusTapTest: $error")
			}
		}

		controller = Robolectric.buildActivity(FragmentActivity::class.java)
		val activity = controller.get()
		activity.setTheme(R.style.Theme_AndroidIDE)
		controller.create()
		// The sheet's collectors start with the activity and look up its BottomSheetBehavior, so
		// place the sheet the way content_editor.xml does before the activity starts.
		sheet = EditorBottomSheet(activity)
		val root = CoordinatorLayout(activity)
		root.addView(
			sheet,
			CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
				behavior = EditorBottomSheetBehavior<EditorBottomSheet>(activity, null)
			},
		)
		activity.setContentView(root)
		controller
			.start()
			.postCreate(null)
			.resume()
			.visible()
	}

	@After
	fun tearDown() {
		controller.pause().stop().destroy()
		Thread.setDefaultUncaughtExceptionHandler(savedHandler)
		assertThat(uncaught).isEmpty()
	}

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

	@Test
	fun `the refusal text restored after indexing is tappable again through setStatus`() {
		bindTap("Quick Build: off - tap for details")
		sheet.setStatus("Indexing project", Gravity.START)
		assertThat(statusText.isClickable).isFalse()

		sheet.setStatus("Quick Build: off - tap for details", Gravity.START)

		assertThat(statusText.isClickable).isTrue()
		statusText.performClick()
		assertThat(taps).isEqualTo(1)
	}
}
