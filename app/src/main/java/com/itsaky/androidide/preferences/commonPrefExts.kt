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

import androidx.preference.Preference
import com.itsaky.androidide.utils.uncheckedCast
import kotlin.reflect.KMutableProperty0

internal abstract class PropertyBasedMultiChoicePreference : MultiChoicePreference() {

/** The checkboxes for this screen, in display order. Build each entry via [propertyEntry]. */
abstract fun getProperties(): List<PreferenceChoices.Entry>

/** One checkbox entry backed by a mutable boolean property, read at its current value. */
protected fun propertyEntry(
	label: String,
	property: KMutableProperty0<Boolean>,
	tooltipTag: String = "",
): PreferenceChoices.Entry = PreferenceChoices.Entry(label, property.get(), property, tooltipTag)

override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> =
	getProperties().toTypedArray()

override fun onChoicesConfirmed(
	preference: Preference,
	entries: Array<PreferenceChoices.Entry>
) {
	entries.forEach { entry ->
	uncheckedCast<KMutableProperty0<Boolean>>(entry.data).set(entry.isChecked)
	}
}
}
