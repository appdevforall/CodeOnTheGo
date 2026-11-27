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

import android.view.MotionEvent

/**
 * Builds a (possibly multi-pointer) [MotionEvent] with explicit [downTime]/[eventTime], so gesture
 * timing is fully controlled by the test. [actionIndex] encodes which pointer a POINTER_DOWN /
 * POINTER_UP action refers to.
 */
fun event(
	action: Int,
	downTime: Long,
	eventTime: Long,
	coords: List<Pair<Float, Float>>,
	actionIndex: Int = 0,
): MotionEvent {
	val properties =
		Array(coords.size) { i ->
			MotionEvent.PointerProperties().apply {
				id = i
				toolType = MotionEvent.TOOL_TYPE_FINGER
			}
		}
	val pointerCoords =
		Array(coords.size) { i ->
			MotionEvent.PointerCoords().apply {
				x = coords[i].first
				y = coords[i].second
				pressure = 1f
				size = 1f
			}
		}
	val maskedAction =
		if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
			action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
		} else {
			action
		}
	return MotionEvent.obtain(
		downTime,
		eventTime,
		maskedAction,
		coords.size,
		properties,
		pointerCoords,
		0,
		0,
		1f,
		1f,
		0,
		0,
		0,
		0,
	)
}
