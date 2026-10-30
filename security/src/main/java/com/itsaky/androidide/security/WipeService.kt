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

import android.app.Activity

/**
 * The entry point to Instant Wipe (ADFA-6510). Implemented in `:app`, which owns the Gradle build
 * service, Termux, the project manager and the Room database that a wipe must tear down. `:security`
 * resolves it through [WipeServiceProvider] on the `Application`, so `:security` keeps no dependency
 * on `:app`.
 */
interface WipeService {
	/**
	 * Engages Instant Wipe from [activity]: present the disguise, then irreversibly destroy the user's
	 * work behind it -- or, when the dry-run override is present ([SecurityOverride.isWipeDryRun]),
	 * enumerate and log the targets without deleting anything.
	 */
	fun trigger(activity: Activity)
}

/**
 * Implemented by the `Application` so any security surface (IDE screens, the disguise, the terminal)
 * can reach the `:app`-owned [WipeService] via
 * `(context.applicationContext as? WipeServiceProvider)?.wipeService`.
 */
interface WipeServiceProvider {
	val wipeService: WipeService
}
