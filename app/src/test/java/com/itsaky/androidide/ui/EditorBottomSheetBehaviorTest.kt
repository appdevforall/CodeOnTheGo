package com.itsaky.androidide.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EditorBottomSheetBehaviorTest {
	private val activity =
		Robolectric
			.buildActivity(Activity::class.java)
			.setup()
			.get()

	private val editorActions = mutableListOf<Int>()

	private val editor =
		View(activity).apply {
			isClickable = true
			setOnTouchListener { _, event ->
				editorActions += event.actionMasked
				false
			}
		}

	private val behavior =
		EditorBottomSheetBehavior<FrameLayout>(activity, null).apply {
			peekHeight = PEEK_HEIGHT
			isHideable = false
		}

	private val sheet = FrameLayout(activity).apply { setPadding(0, TOP_PADDING, 0, 0) }

	private val parent =
		CoordinatorLayout(activity).apply {
			addView(editor, CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
			addView(
				sheet,
				CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).also { it.behavior = behavior },
			)
		}

	private fun laidOut() {
		activity.setContentView(parent)
		shadowOf(Looper.getMainLooper()).idle()
		assertThat(behavior.state).isEqualTo(BottomSheetBehavior.STATE_COLLAPSED)
	}

	private fun dragUpFrom(startY: Float) {
		val downTime = SystemClock.uptimeMillis()
		val x = parent.width / 2f
		val events =
			listOf(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)) +
				(1..DRAG_STEPS).map { step ->
					MotionEvent.obtain(
						downTime,
						downTime + step * STEP_MILLIS,
						MotionEvent.ACTION_MOVE,
						x,
						startY - step * STEP_PX,
						0,
					)
				}
		events.forEach { event ->
			parent.dispatchTouchEvent(event)
			event.recycle()
		}
	}

	@Test
	fun `a drag that starts in the sheet's top padding stays with the view underneath`() {
		laidOut()
		val collapsedTop = sheet.top

		dragUpFrom(collapsedTop + TOP_PADDING / 2f)

		assertThat(behavior.state).isEqualTo(BottomSheetBehavior.STATE_COLLAPSED)
		assertThat(sheet.top).isEqualTo(collapsedTop)
		assertThat(editorActions).doesNotContain(MotionEvent.ACTION_CANCEL)
		assertThat(editorActions.count { it == MotionEvent.ACTION_MOVE }).isEqualTo(DRAG_STEPS)
	}

	@Test
	fun `a drag that starts on the sheet's visible top still drags the sheet`() {
		laidOut()
		val collapsedTop = sheet.top

		dragUpFrom(collapsedTop + TOP_PADDING + (PEEK_HEIGHT - TOP_PADDING) / 2f)

		assertThat(behavior.state).isEqualTo(BottomSheetBehavior.STATE_DRAGGING)
		assertThat(sheet.top).isLessThan(collapsedTop)
	}

	private companion object {
		const val PEEK_HEIGHT = 200
		const val TOP_PADDING = 60
		const val DRAG_STEPS = 6
		const val STEP_PX = 20f
		const val STEP_MILLIS = 16L
	}
}
