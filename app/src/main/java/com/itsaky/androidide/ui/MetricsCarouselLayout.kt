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
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import org.slf4j.LoggerFactory
import kotlin.math.hypot

/**
 * Host for the editor's metrics carousel, which claims horizontal gestures that begin inside it.
 *
 * The carousel pages with a horizontal swipe, but a left-to-right swipe elsewhere in the editor
 * opens the navigation drawer -- documented behaviour, shown in the editor's own onboarding text.
 * Without this, the carousel could only page forwards. Asking every ancestor not to intercept, for
 * the rest of the gesture, hands horizontal drags that start in this strip to [ViewPager2] and
 * leaves the drawer gesture untouched everywhere else.
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

		private var twoFingerDownAt = 0L
		private var twoFingerDownX = 0f
		private var twoFingerDownY = 0f
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
			return super.dispatchTouchEvent(ev)
		}

		override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
			if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
				// Cleared by the framework on the next ACTION_DOWN, so this lasts exactly one gesture.
				parent?.requestDisallowInterceptTouchEvent(true)
			}
			return super.onInterceptTouchEvent(ev)
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
					if (ev.pointerCount == 2) {
						twoFingerTapCandidate = true
						twoFingerDownAt = ev.eventTime
						twoFingerDownX = ev.getX(0)
						twoFingerDownY = ev.getY(0)
					} else {
						// A third finger is not this gesture.
						twoFingerTapCandidate = false
					}
				}

				MotionEvent.ACTION_MOVE -> {
					if (twoFingerTapCandidate && ev.pointerCount >= 1) {
						val travel = hypot(ev.getX(0) - twoFingerDownX, ev.getY(0) - twoFingerDownY)
						if (travel > touchSlop) {
							twoFingerTapCandidate = false
						}
					}
				}

				MotionEvent.ACTION_POINTER_UP -> {
					val heldFor = ev.eventTime - twoFingerDownAt
					log.debug("carousel two-finger up: candidate={} heldFor={}ms limit={}ms", twoFingerTapCandidate, heldFor, tapTimeout)
					if (twoFingerTapCandidate && heldFor <= tapTimeout) {
						twoFingerTapCandidate = false
						log.debug("carousel two-finger tap recognised")
						onTwoFingerTap?.invoke()
					}
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					twoFingerTapCandidate = false
				}
			}
		}

		private val log = LoggerFactory.getLogger(MetricsCarouselLayout::class.java)

		private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

		// A person's two-finger tap is far slower than the single-finger tap timeout: the two
		// fingers land and lift out of step. Anything shorter than a long press counts.
		private val tapTimeout = ViewConfiguration.getLongPressTimeout().toLong()
	}
