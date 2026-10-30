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

package com.itsaky.androidide.activities

import android.app.Activity
import android.os.Bundle
import com.itsaky.androidide.security.InstantHide

/**
 * Launcher entry for the stealth "Code Breaker" activity-aliases (ADFA-40 / ADFA-6510).
 *
 * Separate from [LauncherActivity] purely so its splash theme can avoid the real Code On The Go
 * branding: the OS paints the system starting window from the launched activity's theme before any
 * app code runs, so routing the stealth aliases through [LauncherActivity] (whose `AppTheme.Splash`
 * shows the IDE logo) flashed that logo for ~200ms before the disguise appeared. A stealth alias is
 * only ever enabled while the disguise is engaged, so tapping it must show only the game -- this
 * forwards straight to the cryptogram, never the IDE, reusing a running one so the puzzle survives.
 */
class StealthSplashActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// The lifecycle callback has already shown the disguise if the app is hidden or neutralized.
		if (!isFinishing) InstantHide.showDisguise(this, InstantHide.disguiseIntent(this))
	}
}
