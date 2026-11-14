package com.itsaky.androidide.activities.editor

import android.app.Activity
import android.app.Application
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plugin text follows the editor's font size up, never down (ADFA-7142), and text a plugin adds
 * after its view was built gets the same scale as the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PluginTextScalerTest {
	private var editorScale = 1f
	private val scaler = PluginTextScaler { editorScale }
	private lateinit var activity: Activity
	private lateinit var root: LinearLayout

	@Before
	fun setUp() {
		activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		root = LinearLayout(activity)
	}

	private fun text(px: Float) = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, px) }

	private fun attach() = activity.setContentView(root)

	// The layout pass that follows adding a view, as the window runs it.
	private fun layOut() =
		activity.window.decorView.viewTreeObserver
			.dispatchOnGlobalLayout()

	@Test
	fun textIsScaledWhenTheViewIsTracked() {
		editorScale = 1.5f
		val label = text(20f).also(root::addView)

		scaler.track(root)

		assertThat(label.textSize).isEqualTo(30f)
	}

	@Test
	fun textAddedAfterTheViewWasBuiltIsScaledToo() {
		editorScale = 1.5f
		// As a fragment's view: tracked when created, attached to the window after.
		scaler.track(root)
		attach()

		val row = text(20f).also(root::addView)
		layOut()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun aViewAlreadyAttachedWhenTrackedStillCatchesLaterText() {
		editorScale = 1.5f
		attach()
		scaler.track(root)

		val row = text(20f).also(root::addView)
		layOut()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun laterLayoutsDoNotScaleTextAgain() {
		editorScale = 1.5f
		scaler.track(root)
		attach()
		val row = text(20f).also(root::addView)

		layOut()
		layOut()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun theEditorsSmallestSizeLeavesPluginTextAtItsOwnSize() {
		// The phone ADFA-7142 was found on: the code pinched down to 6.38sp, against a default of 14.
		editorScale = 6.3809667f / 14f
		val label = text(28f).also(root::addView)
		scaler.track(root)
		attach()
		val row = text(28f).also(root::addView)

		layOut()

		assertThat(label.textSize).isEqualTo(28f)
		assertThat(row.textSize).isEqualTo(28f)
	}

	@Test
	fun aPreferenceChangeScalesFromEachViewsOwnSize() {
		editorScale = 2f
		val label = text(20f).also(root::addView)
		scaler.track(root)

		editorScale = 1.5f
		scaler.rescale(root)
		assertThat(label.textSize).isEqualTo(30f)

		editorScale = 0.5f
		scaler.rescale(root)
		assertThat(label.textSize).isEqualTo(20f)
	}

	@Test
	fun textAddedAtTheDefaultSizeIsScaledWhenThePreferenceGrows() {
		scaler.track(root)
		attach()
		val row = text(20f).also(root::addView)
		layOut()
		assertThat(row.textSize).isEqualTo(20f)

		editorScale = 2f
		scaler.rescale(root)

		assertThat(row.textSize).isEqualTo(40f)
	}

	@Test
	fun aDetachedViewStopsWatchingTheWindow() {
		editorScale = 1.5f
		scaler.track(root)
		attach()
		(root.parent as ViewGroup).removeView(root)

		val row = text(20f).also(root::addView)
		layOut()

		assertThat(row.textSize).isEqualTo(20f)
	}
}
