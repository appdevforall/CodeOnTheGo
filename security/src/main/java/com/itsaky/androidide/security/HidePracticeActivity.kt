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
 * Full-screen host for the safe Instant Hide gesture rehearsal (ADFA-40), launched when the user arms
 * Instant Hide. Shows a [HidePracticeOverlayView] and finishes once both gestures are rehearsed (or on
 * back). It is [InstantHide.GesturesExempt], so the real hide triggers do not fire here and practice
 * never engages the disguise for real.
 */
class HidePracticeActivity :
	AppCompatActivity(),
	InstantHide.GesturesExempt {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val overlay =
			HidePracticeOverlayView(this).apply {
				layoutParams =
					ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			}
		setContentView(overlay)
		overlay.startPractice { finish() }
	}
}
