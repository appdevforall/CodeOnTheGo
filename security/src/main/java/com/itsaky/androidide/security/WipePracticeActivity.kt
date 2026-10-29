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

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen host for the safe Instant Wipe gesture rehearsal (ADFA-6510). Shows a
 * [WipePracticeOverlayView] and finishes once the circle is recognised (or on back). It is
 * [InstantHide.GesturesExempt], so the real wipe trigger does not fire here and a practice gesture
 * cannot destroy anything.
 */
class WipePracticeActivity :
	AppCompatActivity(),
	InstantHide.GesturesExempt {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val overlay =
			WipePracticeOverlayView(this).apply {
				layoutParams =
					ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			}
		setContentView(overlay)
		overlay.startPractice { finish() }
	}
}
