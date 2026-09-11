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

package com.itsaky.androidide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.itsaky.androidide.R
import org.slf4j.LoggerFactory
import kotlin.math.hypot

/**
 * Host for the editor's metrics carousel, which claims horizontal gestures that begin inside it.
 *
 * A left-to-right swipe elsewhere in the editor opens the navigation drawer -- documented
 * behaviour, shown in the editor's own onboarding text. Asking every ancestor not to intercept, for
 * the rest of the gesture, keeps horizontal drags that start in this strip for the chart to pan
 * with, and leaves the drawer gesture untouched everywhere else.
 *
 * This covers ancestors that intercept through the view hierarchy. The editor also runs an
 * activity-level [android.view.GestureDetector] from `dispatchTouchEvent`, which never calls
 * `onInterceptTouchEvent` and so cannot be stopped this way; `BaseEditorActivity` excludes this
 * view's bounds there instead, the same way it already excludes the bottom-sheet tab strip.
 *
 * The vertical reveal drag is unaffected: `SwipeRevealLayout` only captures a vertical drag whose
 * touch-down landed in its configured drag handle (the editor app bar), never in this strip.
 */
class MetricsCarouselLayout
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
		defStyleAttr: Int = 0,
	) : ConstraintLayout(context, attrs, defStyleAttr) {
		/**
		 * Invoked on a two-finger tap anywhere in the carousel, which undocks it into a floating
		 * window (ADFA-5486).
		 */
		var onTwoFingerTap: (() -> Unit)? = null

		/** Invoked as each gesture begins. */
		var onTouchDown: (() -> Unit)? = null

		/**
		 * Whether the carousel has been moved out to a floating window.
		 *
		 * Read by the controller: a control whose visibility depends on something else as well --
		 * the battery readout, which only belongs on the page that has one -- cannot be restored by
		 * [setUndocked] alone, so it has to be able to ask.
		 */
		var isUndocked = false
			private set

		/**
		 * Shows either the carousel or the "it is in a floating window" message, never a mix.
		 *
		 * The whole strip switches, not just the pager. The arrows, the snapshot button and the
		 * export button are chrome for a chart that is not here: left behind they sit over the
		 * message, and each is inert anyway because undocking unbinds the controller that listens
		 * to them.
		 *
		 * Keeping the set here was supposed to stop a control added later from being forgotten.
		 * It did not: the battery readout arrived afterwards and was missed, so the readout sat
		 * over the message. A list in one place is still easier to extend than a list at every
		 * call site, but nothing about it is self-maintaining -- what actually guards this is the
		 * test, which enumerates the strip's children rather than naming them.
		 */
		fun setUndocked(undocked: Boolean) {
			isUndocked = undocked
			val carouselIds =
				intArrayOf(
					R.id.metrics_pager,
					R.id.metrics_title,
					R.id.metrics_previous,
					R.id.metrics_next,
					R.id.metrics_snapshot,
					R.id.metrics_export,
				)
			carouselIds.forEach { id ->
				findViewById<View>(id)?.isVisible = !undocked
			}
			// One way only. Undocking hides the battery readout like everything else, but docking
			// must not show it: it belongs to the power page alone, and which page is showing is
			// the controller's to say. It restores the readout on the rebind that follows.
			if (undocked) {
				findViewById<View>(R.id.metrics_battery)?.isVisible = false
			}
			findViewById<View>(R.id.metrics_undocked_message)?.isVisible = undocked
		}

		private var twoFingerDownAt = 0L

		/**
		 * Where each of the two fingers landed. Both are tracked, not just the first: a pinch that
		 * keeps one finger still and spreads the other travels no distance at index 0, so watching
		 * only that finger let a zoom be read as a tap and undock the chart.
		 */
		private val twoFingerDownX = FloatArray(TWO_FINGERS)
		private val twoFingerDownY = FloatArray(TWO_FINGERS)

		/**
		 * The pointers being tracked, by id rather than by index.
		 *
		 * A pointer's index is its slot in the current event and shifts when another pointer
		 * lifts; its id is stable for the life of that finger. Keyed by index, the travel check
		 * could compare one finger's current position against the other's starting point.
		 */
		private val twoFingerIds = IntArray(TWO_FINGERS) { MotionEvent.INVALID_POINTER_ID }
		private var twoFingerTapCandidate = false

		/**
		 * The gesture is watched here rather than in [onInterceptTouchEvent] because ViewPager2's
		 * RecyclerView calls `requestDisallowInterceptTouchEvent` on its parents as soon as a second
		 * pointer lands, and a ViewGroup only calls `onInterceptTouchEvent` while that flag is
		 * clear. Watching from there saw the two fingers arrive and never saw them leave.
		 * `dispatchTouchEvent` is delivered first and is unaffected by the flag.
		 */
		override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
			trackTwoFingerTap(ev)
			if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
				onTouchDown?.invoke()
			}
			return super.dispatchTouchEvent(ev)
		}

		/**
		 * Recognises a two-finger tap: a second finger lands, neither travels far, and one lifts
		 * again quickly. Movement disqualifies it so a pinch is never mistaken for a tap, which
		 * matters because pinch-to-zoom shares this view.
		 */
		private fun trackTwoFingerTap(ev: MotionEvent) {
			if (log.isDebugEnabled) {
				log.debug(
					"carousel touch action={} pointers={} candidate={}",
					ev.actionMasked,
					ev.pointerCount,
					twoFingerTapCandidate,
				)
			}
			when (ev.actionMasked) {
				// Start every gesture clean; a truncated one must not leave a candidate behind.
				MotionEvent.ACTION_DOWN -> {
					twoFingerTapCandidate = false
				}

				MotionEvent.ACTION_POINTER_DOWN -> {
					if (ev.pointerCount == TWO_FINGERS) {
						twoFingerTapCandidate = true
						twoFingerDownAt = ev.eventTime
						for (pointer in 0 until TWO_FINGERS) {
							twoFingerIds[pointer] = ev.getPointerId(pointer)
							twoFingerDownX[pointer] = ev.getX(pointer)
							twoFingerDownY[pointer] = ev.getY(pointer)
						}
					} else {
						// A third finger is not this gesture.
						twoFingerTapCandidate = false
					}
				}

				MotionEvent.ACTION_MOVE -> {
					if (twoFingerTapCandidate) {
						// Either finger travelling means this is a pinch, not a tap. Each is found
						// by its id: a finger that has lifted is simply absent, rather than
						// silently standing in for the other one.
						for (pointer in 0 until TWO_FINGERS) {
							val index = ev.findPointerIndex(twoFingerIds[pointer])
							if (index < 0) {
								continue
							}
							val travel =
								hypot(
									ev.getX(index) - twoFingerDownX[pointer],
									ev.getY(index) - twoFingerDownY[pointer],
								)
							if (travel > touchSlop) {
								twoFingerTapCandidate = false
								break
							}
						}
					}
				}

				MotionEvent.ACTION_POINTER_UP -> {
					val heldFor = ev.eventTime - twoFingerDownAt
					if (log.isDebugEnabled) {
						log.debug(
							"carousel two-finger up: candidate={} heldFor={}ms limit={}ms",
							twoFingerTapCandidate,
							heldFor,
							tapTimeout,
						)
					}
					// Cleared either way: a candidate that has outlasted the tap timeout is over,
					// and leaving it set let a later part of the same gesture be measured against
					// starting points that no longer mean anything.
					val recognised = twoFingerTapCandidate && heldFor <= tapTimeout
					twoFingerTapCandidate = false
					if (recognised) {
						log.debug("carousel two-finger tap recognised")
						onTwoFingerTap?.invoke()
					}
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					twoFingerTapCandidate = false
				}
			}
		}

		private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

		// A person's two-finger tap is far slower than the single-finger tap timeout: the two
		// fingers land and lift out of step. Anything shorter than a long press counts.
		private val tapTimeout = ViewConfiguration.getLongPressTimeout().toLong()

		private companion object {
			private val log = LoggerFactory.getLogger(MetricsCarouselLayout::class.java)

			const val TWO_FINGERS = 2
		}
	}
