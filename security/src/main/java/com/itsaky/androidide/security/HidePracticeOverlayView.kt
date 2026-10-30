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

package com.itsaky.androidide.security

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.itsaky.androidide.resources.R as ResR

/**
 * A safe, full-screen rehearsal of the Instant Hide (ADFA-40) gestures. Two steps: first the
 * three-finger tap that engages the disguise, then the slow double three-finger tap that exits it.
 * It recognises them with the same detectors as the real triggers but only draws touch feedback and,
 * on success, a checkmark -- it never actually hides or swaps the launcher. This lets a user learn the
 * eyes-free gestures without engaging the disguise for real.
 */
@SuppressLint("ClickableViewAccessibility")
class HidePracticeOverlayView
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
		defStyleAttr: Int = 0,
	) : FrameLayout(context, attrs, defStyleAttr) {
		companion object {
			private const val STEP_TRANSITION_DELAY_MS = 1200L
			private const val SUCCESS_FADE_DELAY_MS = 1000L
			private const val SUCCESS_FADE_DURATION_MS = 400L
			private const val TOUCH_DOT_RADIUS_DP = 32f
			private const val INSTRUCTION_TEXT_SIZE_SP = 18f
			private const val FEEDBACK_TEXT_SIZE_SP = 24f
			private const val PADDING_H_DP = 24
			private const val PADDING_TOP_DP = 48
			private const val PADDING_BOTTOM_DP = 24
		}

		private var onGestureSuccess: (() -> Unit)? = null
		private var completed = false
		private var exitStep = false
		private val activeTouchPointers = mutableMapOf<Int, PointF>()
		private val dotRadiusPx = TOUCH_DOT_RADIUS_DP * resources.displayMetrics.density

		private val dotPaint =
			Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = ContextCompat.getColor(context, R.color.success_green)
				style = Paint.Style.FILL
			}

		private val instruction: TextView
		private val feedback: TextView

		// Step 1: the three-finger tap that engages the disguise.
		private val tapDetector =
			GestureDetectorHelper.ThreeFingerTapDetector(context) {
				if (!completed && !exitStep) onFirstStep()
			}

		// Step 2: the slow double three-finger tap that exits it.
		private val exitDetector =
			GestureDetectorHelper.SlowDoubleThreeFingerTapDetector(context) {
				if (!completed && exitStep) onSuccess()
			}

		init {
			setWillNotDraw(false)
			setBackgroundColor(ContextCompat.getColor(context, R.color.practice_overlay_background))
			val density = resources.displayMetrics.density

			instruction =
				TextView(context).apply {
					textSize = INSTRUCTION_TEXT_SIZE_SP
					setTextColor(Color.WHITE)
					gravity = Gravity.CENTER
					setPadding(
						(PADDING_H_DP * density).toInt(),
						(PADDING_TOP_DP * density).toInt(),
						(PADDING_H_DP * density).toInt(),
						(PADDING_BOTTOM_DP * density).toInt(),
					)
					text = context.getString(ResR.string.practice_hide_instructions)
				}
			addView(instruction, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

			feedback =
				TextView(context).apply {
					textSize = FEEDBACK_TEXT_SIZE_SP
					setTextColor(ContextCompat.getColor(context, R.color.success_green))
					gravity = Gravity.CENTER
					visibility = GONE
					text =
						context.getString(
							ResR.string.gesture_success_with_check,
							context.getString(ResR.string.gesture_success),
						)
				}
			addView(
				feedback,
				LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER },
			)
		}

		/** Resets to step one and arms the detectors; [onSuccess] fires once, after the final fade. */
		fun startPractice(onSuccess: () -> Unit) {
			onGestureSuccess = onSuccess
			completed = false
			exitStep = false
			feedback.visibility = GONE
			activeTouchPointers.clear()
			instruction.text = context.getString(ResR.string.practice_hide_instructions)
			alpha = 1f
			visibility = VISIBLE
		}

		override fun onTouchEvent(event: MotionEvent): Boolean {
			if (completed) return true
			if (exitStep) exitDetector.onTouchEvent(event) else tapDetector.onTouchEvent(event)

			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					activeTouchPointers.clear()
					activeTouchPointers[event.getPointerId(0)] = PointF(event.x, event.y)
				}

				MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
					for (i in 0 until event.pointerCount) {
						activeTouchPointers[event.getPointerId(i)] = PointF(event.getX(i), event.getY(i))
					}
				}

				MotionEvent.ACTION_POINTER_UP -> {
					activeTouchPointers.remove(event.getPointerId(event.actionIndex))
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					activeTouchPointers.clear()
				}
			}
			invalidate()
			return true
		}

		override fun onDraw(canvas: Canvas) {
			super.onDraw(canvas)
			for (point in activeTouchPointers.values) {
				canvas.drawCircle(point.x, point.y, dotRadiusPx, dotPaint)
			}
		}

		private fun onFirstStep() {
			feedback.visibility = VISIBLE
			activeTouchPointers.clear()
			invalidate()
			postDelayed({
				feedback.visibility = GONE
				exitStep = true
				instruction.text = context.getString(ResR.string.practice_hide_exit_instructions)
				activeTouchPointers.clear()
				invalidate()
			}, STEP_TRANSITION_DELAY_MS)
		}

		private fun onSuccess() {
			completed = true
			activeTouchPointers.clear()
			invalidate()
			feedback.visibility = VISIBLE
			animate()
				.alpha(0f)
				.setStartDelay(SUCCESS_FADE_DELAY_MS)
				.setDuration(SUCCESS_FADE_DURATION_MS)
				.setListener(
					object : AnimatorListenerAdapter() {
						override fun onAnimationEnd(animation: Animator) {
							visibility = GONE
							onGestureSuccess?.invoke()
						}
					},
				)
		}
	}
