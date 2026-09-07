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

package com.itsaky.androidide.utils

import android.view.View

/**
 * Stops this view answering a long press.
 *
 * `setOnLongClickListener(null)` alone is not enough: [View.setOnLongClickListener] sets
 * `isLongClickable` when it installs a listener but does not unset it when the listener is
 * removed, so the view goes on consuming long presses -- and showing the system's own
 * "performLongClick" feedback -- for help it no longer offers. Every teardown that clears a
 * long-press help listener wants both halves, so it is one call.
 */
fun View.clearLongPressHelp() {
	setOnLongClickListener(null)
	isLongClickable = false
	// The hold is timed by a touch listener rather than the framework (ADFA-5554), so leaving that
	// installed would keep the view swallowing every touch -- and performing its own clicks -- for
	// help it no longer offers.
	setOnTouchListener(null)
}
