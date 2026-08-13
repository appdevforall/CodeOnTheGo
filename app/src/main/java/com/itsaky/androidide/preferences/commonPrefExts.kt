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

/** One checkbox: its label, the property it toggles, and its own tooltip tag (if any). */
data class PropertyEntry(
	val label: String,
	val property: KMutableProperty0<Boolean>,
	val tooltipTag: String = "",
)

/** The checkboxes for this screen, in display order; each entry's [PropertyEntry.tooltipTag] is that choice's own long-press help. */
abstract fun getProperties(): List<PropertyEntry>

override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
	val properties = getProperties()
	return Array(properties.size) { i ->
	val entry = properties[i]
	PreferenceChoices.Entry(entry.label, entry.property.get(), entry.property, entry.tooltipTag)
	}
}

override fun onChoicesConfirmed(
	preference: Preference,
	entries: Array<PreferenceChoices.Entry>
) {
	entries.forEach { entry ->
	uncheckedCast<KMutableProperty0<Boolean>>(entry.data).set(entry.isChecked)
	}
}
}
