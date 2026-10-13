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

import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Touch-gesture detectors for the "Instant Hide" security feature (ADFA-40): a single, eyes-free
 * gesture that disguises the IDE, and a deliberate counter-gesture that exits the disguise.
 *
 * Timing is read from [MotionEvent.getDownTime] / [MotionEvent.getEventTime] rather than the wall
 * clock, so a gesture's duration is the input system's own measurement (and so the detectors are
 * deterministically testable from synthesised events).
 *
 * [CircleGestureDetector] drives Instant Wipe (ADFA-6510): a deliberate single-finger circular sweep,
 * shaped to be hard to perform by accident while scrolling an editor or the terminal.
 */
object GestureDetectorHelper {
	// Three-finger tap (enter disguise).
	const val REQUIRED_POINTER_COUNT = 3
	const val MAX_TAP_DURATION_MS = 600L
	const val MAX_TAP_DRIFT_DP = 50f

	// Slow double three-finger tap (exit disguise): the gap between the two taps.
	const val MIN_DOUBLE_TAP_GAP_MS = 150L
	const val MAX_DOUBLE_TAP_GAP_MS = 1600L

	// Single-finger circular sweep (Instant Wipe). A near-full rotation spanning most of the screen
	// width: deliberately awkward so an editor/terminal scroll or a scribble cannot accumulate it.
	const val MIN_STROKE_POINTS = 10
	const val MIN_CIRCLE_DURATION_MS = 400L
	const val MIN_CIRCLE_WIDTH_FRACTION = 0.70f
	const val MIN_CIRCLE_SWEEP_DEGREES = 330.0

	/**
	 * Fires [onDetected] with the tap's up-event time when a single three-finger tap completes
	 * within [MAX_TAP_DURATION_MS] and with no pointer drifting more than [MAX_TAP_DRIFT_DP].
	 */
	class ThreeFingerTapDetector(
		context: Context,
		private val onDetected: (upEventTime: Long) -> Unit,
	) {
		private var maxPointerCount = 0
		private val initialPositions = mutableMapOf<Int, PointF>()
		private var driftExceeded = false
		private val maxDriftPx = MAX_TAP_DRIFT_DP * context.resources.displayMetrics.density

		fun onTouchEvent(event: MotionEvent): Boolean {
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					maxPointerCount = 1
					driftExceeded = false
					initialPositions.clear()
					initialPositions[event.getPointerId(0)] = PointF(event.getX(0), event.getY(0))
				}

				MotionEvent.ACTION_POINTER_DOWN -> {
					maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
					val idx = event.actionIndex
					initialPositions[event.getPointerId(idx)] = PointF(event.getX(idx), event.getY(idx))
				}

				MotionEvent.ACTION_MOVE -> {
					for (i in 0 until event.pointerCount) {
						val init = initialPositions[event.getPointerId(i)] ?: continue
						val dx = event.getX(i) - init.x
						val dy = event.getY(i) - init.y
						if (sqrt(dx * dx + dy * dy) > maxDriftPx) {
							driftExceeded = true
						}
					}
				}

				MotionEvent.ACTION_POINTER_UP -> {
					maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
				}

				MotionEvent.ACTION_UP -> {
					val duration = event.eventTime - event.downTime
					if (maxPointerCount >= REQUIRED_POINTER_COUNT &&
						duration <= MAX_TAP_DURATION_MS &&
						!driftExceeded
					) {
						onDetected(event.eventTime)
						return true
					}
				}

				MotionEvent.ACTION_CANCEL -> {
					initialPositions.clear()
				}
			}
			return false
		}
	}

	/**
	 * Fires [onDetected] on two consecutive three-finger taps whose up-events are separated by a
	 * deliberate gap in [[MIN_DOUBLE_TAP_GAP_MS], [MAX_DOUBLE_TAP_GAP_MS]]. A tap outside that window
	 * becomes the new first tap rather than being discarded, so a mistimed attempt can be retried.
	 */
	class SlowDoubleThreeFingerTapDetector(
		context: Context,
		private val onDetected: () -> Unit,
	) {
		private var firstTapUpTime = 0L
		private val singleDetector =
			ThreeFingerTapDetector(context) { upEventTime ->
				if (firstTapUpTime > 0L) {
					val gap = upEventTime - firstTapUpTime
					if (gap in MIN_DOUBLE_TAP_GAP_MS..MAX_DOUBLE_TAP_GAP_MS) {
						firstTapUpTime = 0L
						onDetected()
						return@ThreeFingerTapDetector
					}
				}
				firstTapUpTime = upEventTime
			}

		fun onTouchEvent(event: MotionEvent): Boolean = singleDetector.onTouchEvent(event)
	}

	/**
	 * Fires [onDetected] when a single-finger stroke traces a near-complete circle: one pointer for
	 * the whole stroke, at least [MIN_STROKE_POINTS] move samples lasting at least
	 * [MIN_CIRCLE_DURATION_MS], a bounding box at least [MIN_CIRCLE_WIDTH_FRACTION] of the screen wide,
	 * and a cumulative signed sweep around the box centre of at least [MIN_CIRCLE_SWEEP_DEGREES]. A
	 * second pointer disqualifies the stroke, so it never collides with the tap gestures.
	 */
	class CircleGestureDetector(
		context: Context,
		private val onDetected: () -> Unit,
	) {
		private val screenWidthPx = context.resources.displayMetrics.widthPixels
		private val points = mutableListOf<PointF>()
		private var singleFinger = true

		fun onTouchEvent(event: MotionEvent): Boolean {
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					points.clear()
					singleFinger = true
				}

				// A second finger means this is one of the tap gestures, not a wipe circle.
				MotionEvent.ACTION_POINTER_DOWN -> {
					singleFinger = false
				}

				MotionEvent.ACTION_MOVE -> {
					if (singleFinger) points.add(PointF(event.getX(0), event.getY(0)))
				}

				MotionEvent.ACTION_UP -> {
					val duration = event.eventTime - event.downTime
					if (singleFinger &&
						points.size >= MIN_STROKE_POINTS &&
						duration >= MIN_CIRCLE_DURATION_MS &&
						isCircle()
					) {
						onDetected()
						return true
					}
				}

				MotionEvent.ACTION_CANCEL -> {
					points.clear()
				}
			}
			return false
		}

		/**
		 * True when the stroke spans enough width and sweeps a near-full turn around its bounding-box
		 * centre. Each step's angle delta is normalised to [-PI, PI] so the running sum measures *net*
		 * rotation -- a back-and-forth scribble cancels out instead of accumulating arc length.
		 */
		private fun isCircle(): Boolean {
			val minX = points.minOf { it.x }
			val maxX = points.maxOf { it.x }
			val minY = points.minOf { it.y }
			val maxY = points.maxOf { it.y }
			if (maxX - minX < screenWidthPx * MIN_CIRCLE_WIDTH_FRACTION) return false

			val centerX = (minX + maxX) / 2f
			val centerY = (minY + maxY) / 2f
			var sweep = 0.0
			for (i in 1 until points.size) {
				val a1 = atan2((points[i - 1].y - centerY).toDouble(), (points[i - 1].x - centerX).toDouble())
				val a2 = atan2((points[i].y - centerY).toDouble(), (points[i].x - centerX).toDouble())
				var delta = a2 - a1
				while (delta > Math.PI) delta -= 2 * Math.PI
				while (delta < -Math.PI) delta += 2 * Math.PI
				sweep += delta
			}
			return abs(Math.toDegrees(sweep)) >= MIN_CIRCLE_SWEEP_DEGREES
		}
	}
}
