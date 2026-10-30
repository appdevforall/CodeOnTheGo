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
import android.graphics.Path
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.itsaky.androidide.resources.R as ResR

/**
 * A safe, full-screen rehearsal of the Instant Wipe circular-sweep gesture (ADFA-6510). It draws the
 * live finger trail and recognises the gesture with the same [GestureDetectorHelper.CircleGestureDetector]
 * as the real trigger, but on success it only shows a checkmark and invokes the success callback -- it
 * never wipes. This lets a user learn an irreversible gesture without risk.
 */
@SuppressLint("ClickableViewAccessibility")
class WipePracticeOverlayView
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
		defStyleAttr: Int = 0,
	) : FrameLayout(context, attrs, defStyleAttr) {
		companion object {
			private const val SUCCESS_FADE_DELAY_MS = 1000L
			private const val SUCCESS_FADE_DURATION_MS = 400L
			private const val STROKE_WIDTH_DP = 10f
			private const val INSTRUCTION_TEXT_SIZE_SP = 18f
			private const val FEEDBACK_TEXT_SIZE_SP = 24f
			private const val PADDING_H_DP = 24
			private const val PADDING_TOP_DP = 48
			private const val PADDING_BOTTOM_DP = 24
		}

		private var onGestureSuccess: (() -> Unit)? = null
		private var completed = false
		private val strokePath = Path()

		private val strokePaint =
			Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = ContextCompat.getColor(context, R.color.touch_stroke_blue)
				strokeWidth = STROKE_WIDTH_DP * resources.displayMetrics.density
				style = Paint.Style.STROKE
				strokeCap = Paint.Cap.ROUND
				strokeJoin = Paint.Join.ROUND
			}

		private val instruction: TextView
		private val feedback: TextView

		private val circleDetector =
			GestureDetectorHelper.CircleGestureDetector(context) {
				if (!completed) handleSuccess()
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
					text =
						context.getString(
							ResR.string.practice_wipe_instructions,
							(GestureDetectorHelper.MIN_CIRCLE_WIDTH_FRACTION * 100).toInt(),
						)
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

		/** Resets the overlay and arms the practice detector; [onSuccess] fires once, after the fade. */
		fun startPractice(onSuccess: () -> Unit) {
			onGestureSuccess = onSuccess
			completed = false
			feedback.visibility = GONE
			strokePath.reset()
			alpha = 1f
			visibility = VISIBLE
		}

		override fun onTouchEvent(event: MotionEvent): Boolean {
			if (completed) return true
			circleDetector.onTouchEvent(event)
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					strokePath.reset()
					strokePath.moveTo(event.x, event.y)
				}

				MotionEvent.ACTION_MOVE -> {
					if (event.pointerCount == 1) strokePath.lineTo(event.x, event.y)
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					strokePath.reset()
				}
			}
			invalidate()
			return true
		}

		override fun onDraw(canvas: Canvas) {
			super.onDraw(canvas)
			canvas.drawPath(strokePath, strokePaint)
		}

		private fun handleSuccess() {
			completed = true
			strokePath.reset()
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
