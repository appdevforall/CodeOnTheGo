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
import kotlin.math.sqrt

/**
 * Touch-gesture detectors for the "Instant Hide" security feature (ADFA-40): a single, eyes-free
 * gesture that disguises the IDE, and a deliberate counter-gesture that exits the disguise.
 *
 * Timing is read from [MotionEvent.getDownTime] / [MotionEvent.getEventTime] rather than the wall
 * clock, so a gesture's duration is the input system's own measurement (and so the detectors are
 * deterministically testable from synthesised events).
 *
 * The self-destruct circle detector from the original mockup is deliberately not ported here;
 * self-destruct is out of scope for this change.
 */
object GestureDetectorHelper {
	// Three-finger tap (enter disguise).
	const val REQUIRED_POINTER_COUNT = 3
	const val MAX_TAP_DURATION_MS = 600L
	const val MAX_TAP_DRIFT_DP = 50f

	// Slow double three-finger tap (exit disguise): the gap between the two taps.
	const val MIN_DOUBLE_TAP_GAP_MS = 150L
	const val MAX_DOUBLE_TAP_GAP_MS = 1600L

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
}
