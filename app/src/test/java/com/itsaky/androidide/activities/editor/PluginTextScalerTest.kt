package com.itsaky.androidide.activities.editor

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

	// The window's step before it draws a frame. Returns whether that frame was skipped.
	private fun drawFrame(window: View = activity.window.decorView) = window.viewTreeObserver.dispatchOnPreDraw()

	// How many pre-draw listeners the window has, read where ViewTreeObserver keeps them.
	private fun preDrawListeners(): Int {
		val field = ViewTreeObserver::class.java.getDeclaredField("mOnPreDrawListeners").apply { isAccessible = true }
		val listeners = field.get(activity.window.decorView.viewTreeObserver) ?: return 0
		return listeners.javaClass
			.getDeclaredMethod("size")
			.apply { isAccessible = true }
			.invoke(listeners) as Int
	}

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
		drawFrame()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun aViewAlreadyAttachedWhenTrackedStillCatchesLaterText() {
		editorScale = 1.5f
		attach()
		scaler.track(root)

		val row = text(20f).also(root::addView)
		drawFrame()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun laterFramesDoNotScaleTextAgain() {
		editorScale = 1.5f
		scaler.track(root)
		attach()
		val row = text(20f).also(root::addView)

		assertThat(drawFrame()).isTrue()
		assertThat(drawFrame()).isFalse()

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

		drawFrame()

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
		drawFrame()
		assertThat(row.textSize).isEqualTo(20f)

		editorScale = 2f
		scaler.rescale(root)

		assertThat(row.textSize).isEqualTo(40f)
	}

	@Test
	fun aDetachedViewStopsWatchingTheWindow() {
		editorScale = 1.5f
		attach()
		val before = preDrawListeners()
		scaler.track(root)
		(root.parent as ViewGroup).removeView(root)

		val row = text(20f).also(root::addView)
		drawFrame()

		assertThat(row.textSize).isEqualTo(20f)
		assertThat(preDrawListeners()).isEqualTo(before)
	}

	@Test
	fun aRootLeavingTheWindowLeavesTheOtherRootsWatched() {
		// Two plugin tabs: one is closed, the other stays.
		editorScale = 1.5f
		val closed = LinearLayout(activity).also(root::addView)
		val open = LinearLayout(activity).also(root::addView)
		scaler.track(closed)
		scaler.track(open)
		attach()

		root.removeView(closed)
		val row = text(20f).also(open::addView)
		drawFrame()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun aViewReinsertedAfterThePreferenceChangedMatchesAFreshOne() {
		editorScale = 1.5f
		attach()
		scaler.track(root)
		val pooled = text(20f).also(root::addView)
		drawFrame()
		assertThat(pooled.textSize).isEqualTo(30f)
		root.removeView(pooled)

		editorScale = 2f
		scaler.rescale(root)
		root.addView(pooled)
		val fresh = text(20f).also(root::addView)
		drawFrame()

		assertThat(pooled.textSize).isEqualTo(40f)
		assertThat(fresh.textSize).isEqualTo(40f)
	}

	@Test
	fun aViewReinsertedAfterThePreferenceFellToTheDefaultGoesBackToItsOwnSize() {
		editorScale = 1.5f
		attach()
		scaler.track(root)
		val pooled = text(20f).also(root::addView)
		drawFrame()
		root.removeView(pooled)

		editorScale = 1f
		scaler.rescale(root)
		root.addView(pooled)
		drawFrame()

		assertThat(pooled.textSize).isEqualTo(20f)
	}

	@Test
	fun textAddedWithNoLayoutPassIsScaledBeforeItIsDrawn() {
		// A scrolling RecyclerView or ListView adds rows and draws them with no layout pass in
		// between, so only the step before drawing sees them.
		editorScale = 1.5f
		scaler.track(root)
		attach()
		val row = text(20f).also(root::addView)

		val skipped = drawFrame()

		assertThat(row.textSize).isEqualTo(30f)
		// The frame that found it is skipped, so the row is never drawn at its old size.
		assertThat(skipped).isTrue()
	}

	@Test
	fun hiddenTextIsScaledOnTheFrameThatShowsIt() {
		editorScale = 1.5f
		scaler.track(root)
		attach()
		root.visibility = View.GONE
		val row = text(20f).also(root::addView)

		assertThat(drawFrame()).isFalse()
		assertThat(row.textSize).isEqualTo(20f)

		root.visibility = View.VISIBLE
		drawFrame()
		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun aSizeThePluginSetsLaterIsScaledFromThere() {
		// Say a recycled row whose binding sets its text size again.
		editorScale = 2f
		scaler.track(root)
		attach()
		val row = text(20f).also(root::addView)
		drawFrame()
		assertThat(row.textSize).isEqualTo(40f)

		row.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)
		drawFrame()

		assertThat(row.textSize).isEqualTo(60f)
	}

	@Test
	fun aPreferenceChangeKeepsASizeThePluginSetLater() {
		editorScale = 2f
		val row = text(20f).also(root::addView)
		scaler.track(root)
		row.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)

		editorScale = 1.5f
		scaler.rescale(root)

		assertThat(row.textSize).isEqualTo(45f)
	}

	@Test
	fun autoSizedTextIsLeftAloneAndNeverHoldsUpAFrame() {
		// setTextSize does nothing to auto-sized text, so it would look resized on every frame.
		editorScale = 2f
		val fitted = text(20f).apply { setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM) }
		root.addView(fitted)
		scaler.track(root)
		attach()
		val size = fitted.textSize

		assertThat(listOf(drawFrame(), drawFrame())).containsExactly(false, false)
		assertThat(fitted.textSize).isEqualTo(size)
	}

	@Test
	fun aRootTrackedAgainKeepsOneWatcher() {
		// A fragment that hands back the same view each time its view is recreated.
		attach()
		val before = preDrawListeners()

		scaler.track(root)
		scaler.track(root)

		assertThat(preDrawListeners()).isEqualTo(before + 1)
	}

	@Test
	fun aPluginThatResizesItsTextEveryFrameNeverStopsTheWindowDrawing() {
		// Say a fit-to-width routine that sets the size again on every layout.
		editorScale = 2f
		scaler.track(root)
		attach()
		val fitted = text(25f).also(root::addView)

		val skipped =
			(1..6).map {
				fitted.setTextSize(TypedValue.COMPLEX_UNIT_PX, 25f)
				drawFrame()
			}

		assertThat(skipped.zipWithNext().none { (first, second) -> first && second }).isTrue()
		assertThat(skipped).contains(true)
	}

	@Test
	fun aRootAttachedAgainCatchesTextAddedAfterward() {
		// A tab whose view is taken off the window and put back, as a pager does.
		editorScale = 1.5f
		scaler.track(root)
		attach()
		(root.parent as ViewGroup).removeView(root)
		attach()

		val row = text(20f).also(root::addView)
		drawFrame()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun twoPluginsResizingTheirTextEveryFrameStillLetEveryOtherFrameDraw() {
		// The window skips a frame if any listener asks. Two plugin tabs, each rescaled on every
		// frame and starting a frame apart, must not take turns skipping every one of them.
		editorScale = 2f
		val first = LinearLayout(activity).also(root::addView)
		val second = LinearLayout(activity).also(root::addView)
		val fittedFirst = text(25f).also(first::addView)
		val fittedSecond = text(25f).also(second::addView)
		scaler.track(first)
		scaler.track(second)
		attach()

		val skipped =
			(1..6).map { frame ->
				fittedFirst.setTextSize(TypedValue.COMPLEX_UNIT_PX, 25f)
				if (frame > 1) fittedSecond.setTextSize(TypedValue.COMPLEX_UNIT_PX, 25f)
				drawFrame()
			}

		assertThat(skipped.zipWithNext().none { (first, second) -> first && second }).isTrue()
		assertThat(skipped).contains(true)
	}

	@Test
	fun aPluginDialogSkipsFramesApartFromTheEditorWindow() {
		// A plugin's dialog or bottom sheet is a window of its own, drawn in turn with the editor's.
		// What one window skipped must not decide whether the other may skip.
		editorScale = 2f
		val sheet = LinearLayout(activity)
		val dialog = Dialog(activity).apply { setContentView(sheet) }
		dialog.show()
		attach()
		shadowOf(Looper.getMainLooper()).idle()
		assertThat(sheet.isAttachedToWindow).isTrue()
		assertThat(sheet.viewTreeObserver).isNotSameInstanceAs(root.viewTreeObserver)
		val inEditor = text(25f).also(root::addView)
		val inSheet = text(25f).also(sheet::addView)
		scaler.track(root)
		scaler.track(sheet)

		val editorSkipped =
			(1..6).map {
				inEditor.setTextSize(TypedValue.COMPLEX_UNIT_PX, 25f)
				inSheet.setTextSize(TypedValue.COMPLEX_UNIT_PX, 25f)
				drawFrame().also { drawFrame(dialog.window!!.decorView) }
			}

		assertThat(editorSkipped.zipWithNext().none { (first, second) -> first && second }).isTrue()
		assertThat(editorSkipped).contains(true)
	}
}
