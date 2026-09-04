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
import androidx.constraintlayout.widget.ConstraintLayout

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
		override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
			if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
				// Cleared by the framework on the next ACTION_DOWN, so this lasts exactly one gesture.
				parent?.requestDisallowInterceptTouchEvent(true)
			}
			return super.onInterceptTouchEvent(ev)
		}
	}
