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

import android.os.Parcel
import android.os.Parcelable
import com.itsaky.androidide.idetooltips.TooltipTag

/**
 * The preferences for the IDE.
 *
 * @author Akash Yadav
 */
data object IDEPreferences : BaseIDEPreferences() {
	override val children: List<IPreference> = mutableListOf()

	// Never actually read at runtime today - PreferencesActivity passes the literal
	// TooltipTag.PREFS_TOP constant for the root fragment instead of this property - but this is
	// the root container this constant represents, so give it the matching value rather than an
	// unused empty string.
	override val tooltipTag: String = TooltipTag.PREFS_TOP

	override fun describeContents(): Int = 0

	override fun writeToParcel(
		dest: Parcel,
		flags: Int,
	) {}

	@JvmField
	val CREATOR =
		object : Parcelable.Creator<IDEPreferences> {
			override fun createFromParcel(source: Parcel?): IDEPreferences = IDEPreferences

			override fun newArray(size: Int): Array<IDEPreferences> = Array(size) { IDEPreferences }
		}
}
