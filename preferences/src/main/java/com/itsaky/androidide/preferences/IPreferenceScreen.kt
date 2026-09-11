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

package com.itsaky.androidide.preferences

import android.content.Context
import androidx.preference.Preference

/**
 * A preference screen which will be shown in a fragment.
 *
 * @author Akash Yadav
 */
abstract class IPreferenceScreen : IPreferenceGroup() {
	// Re-abstracted rather than inheriting IPreference's "" default: a screen's tag is what a
	// long-press falls back to for every row on it (see IDEPreferencesFragment), including empty
	// RecyclerView space, so silently omitting it here is a real regression (it did happen once in
	// this codebase's history), not just a missing per-row nicety. All current screens already
	// supply a real tag, so this is a compile-time guarantee, not a behavior change.
	abstract override val tooltipTag: String

	override fun onCreatePreference(context: Context): Preference = Preference(context)
}
