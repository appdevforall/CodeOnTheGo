package com.itsaky.androidide.activities.editor

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
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
	private val scaler = PluginTextScaler(1f)
	private lateinit var activity: Activity
	private lateinit var root: LinearLayout

	@Before
	fun setUp() {
		activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		root = LinearLayout(activity)
	}

	private fun text(px: Float) = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, px) }

	// Counts how often its size is read: each walk over it reads the size once.
	private class CountingTextView(
		context: Context,
	) : TextView(context) {
		var sizeReads = 0

		override fun getTextSize(): Float {
			sizeReads++
			return super.getTextSize()
		}
	}

	private fun countingText(px: Float) = CountingTextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, px) }

	// Counts how often a frame asks whether it is shown, the first thing a frame checks per root.
	private class CountingRoot(
		context: Context,
	) : LinearLayout(context) {
		var shownChecks = 0

		override fun isShown(): Boolean {
			shownChecks++
			return super.isShown()
		}
	}

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
		scaler.onEditorFontScaleChanged(1.5f)
		val label = text(20f).also(root::addView)

		scaler.track(root)

		assertThat(label.textSize).isEqualTo(30f)
	}

	@Test
	fun textAddedAfterTheViewWasBuiltIsScaledToo() {
		scaler.onEditorFontScaleChanged(1.5f)
		// As a fragment's view: tracked when created, attached to the window after.
		scaler.track(root)
		attach()

		val row = text(20f).also(root::addView)
		drawFrame()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun aViewAlreadyAttachedWhenTrackedStillCatchesLaterText() {
		scaler.onEditorFontScaleChanged(1.5f)
		attach()
		scaler.track(root)

		val row = text(20f).also(root::addView)
		drawFrame()

		assertThat(row.textSize).isEqualTo(30f)
	}

	@Test
	fun laterFramesDoNotScaleTextAgain() {
		scaler.onEditorFontScaleChanged(1.5f)
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
		scaler.onEditorFontScaleChanged(6.3809667f / 14f)
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
		scaler.onEditorFontScaleChanged(2f)
		val label = text(20f).also(root::addView)
		scaler.track(root)

		scaler.onEditorFontScaleChanged(1.5f)
		assertThat(label.textSize).isEqualTo(30f)

		scaler.onEditorFontScaleChanged(0.5f)
		assertThat(label.textSize).isEqualTo(20f)
	}

	@Test
	fun textAddedAtTheDefaultSizeIsScaledWhenThePreferenceGrows() {
		scaler.track(root)
		attach()
		val row = text(20f).also(root::addView)
		drawFrame()
		assertThat(row.textSize).isEqualTo(20f)

		scaler.onEditorFontScaleChanged(2f)

		assertThat(row.textSize).isEqualTo(40f)
	}

	@Test
	fun aDetachedViewStopsWatchingTheWindow() {
		scaler.onEditorFontScaleChanged(1.5f)
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
		scaler.onEditorFontScaleChanged(1.5f)
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
		scaler.onEditorFontScaleChanged(1.5f)
		attach()
		scaler.track(root)
		val pooled = text(20f).also(root::addView)
		drawFrame()
		assertThat(pooled.textSize).isEqualTo(30f)
		root.removeView(pooled)

		scaler.onEditorFontScaleChanged(2f)
		root.addView(pooled)
		val fresh = text(20f).also(root::addView)
		drawFrame()

		assertThat(pooled.textSize).isEqualTo(40f)
		assertThat(fresh.textSize).isEqualTo(40f)
	}

	@Test
	fun aViewReinsertedAfterThePreferenceFellToTheDefaultGoesBackToItsOwnSize() {
		scaler.onEditorFontScaleChanged(1.5f)
		attach()
		scaler.track(root)
		val pooled = text(20f).also(root::addView)
		drawFrame()
		root.removeView(pooled)

		scaler.onEditorFontScaleChanged(1f)
		root.addView(pooled)
		drawFrame()

		assertThat(pooled.textSize).isEqualTo(20f)
	}

	@Test
	fun textAddedWithNoLayoutPassIsScaledBeforeItIsDrawn() {
		// A scrolling RecyclerView or ListView adds rows and draws them with no layout pass in
		// between, so only the step before drawing sees them.
		scaler.onEditorFontScaleChanged(1.5f)
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
		scaler.onEditorFontScaleChanged(1.5f)
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
		scaler.onEditorFontScaleChanged(2f)
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
		scaler.onEditorFontScaleChanged(2f)
		val row = text(20f).also(root::addView)
		scaler.track(root)
		row.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)

		scaler.onEditorFontScaleChanged(1.5f)

		assertThat(row.textSize).isEqualTo(45f)
	}

	@Test
	fun autoSizedTextIsLeftAloneAndNeverHoldsUpAFrame() {
		// setTextSize does nothing to auto-sized text, so it would look resized on every frame.
		scaler.onEditorFontScaleChanged(2f)
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
		scaler.onEditorFontScaleChanged(2f)
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
		scaler.onEditorFontScaleChanged(1.5f)
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
		scaler.onEditorFontScaleChanged(2f)
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
		scaler.onEditorFontScaleChanged(2f)
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

	@Test
	fun aPluginViewNestedInAnotherIsWalkedOncePerFrame() {
		// A plugin fragment inside another plugin fragment's view: both views are tracked, the inner
		// one first, as its view is created first.
		scaler.onEditorFontScaleChanged(2f)
		val inner = LinearLayout(activity).also(root::addView)
		val label = countingText(20f).also(inner::addView)
		scaler.track(inner)
		scaler.track(root)
		attach()
		drawFrame()
		label.sizeReads = 0

		drawFrame()

		assertThat(label.sizeReads).isEqualTo(1)
	}

	@Test
	fun onceTheScaleIsBackTo1FramesNoLongerWalkThePluginViews() {
		scaler.onEditorFontScaleChanged(2f)
		val label = countingText(20f).also(root::addView)
		scaler.track(root)
		attach()
		drawFrame()

		scaler.onEditorFontScaleChanged(1f)
		label.sizeReads = 0
		drawFrame()

		assertThat(label.sizeReads).isEqualTo(0)
		assertThat(label.textSize).isEqualTo(20f)
	}

	@Test
	fun atScale1AFrameLooksAtNoPluginRoot() {
		val counted = CountingRoot(activity).also { it.addView(text(20f)) }
		scaler.track(counted)
		activity.setContentView(counted)
		counted.shownChecks = 0

		drawFrame()

		assertThat(counted.shownChecks).isEqualTo(0)
	}

	@Test
	fun aPooledViewThePluginResizedOffTheWindowIsScaledFromItsNewSize() {
		scaler.onEditorFontScaleChanged(2f)
		attach()
		scaler.track(root)
		val pooled = text(20f).also(root::addView)
		drawFrame()
		root.removeView(pooled)
		// Bound again while pooled, at a size of the plugin's own.
		pooled.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)

		scaler.onEditorFontScaleChanged(3f)

		assertThat(pooled.textSize).isEqualTo(90f)
	}

	@Test
	fun aScaleChangeThatStaysAt1LeavesThePluginTextAlone() {
		// Pinching the code from 10 to 8: both below the editor's default.
		scaler.onEditorFontScaleChanged(10f / 14f)
		val label = countingText(20f).also(root::addView)
		scaler.track(root)
		label.sizeReads = 0

		scaler.onEditorFontScaleChanged(8f / 14f)

		assertThat(label.sizeReads).isEqualTo(0)
	}
}
