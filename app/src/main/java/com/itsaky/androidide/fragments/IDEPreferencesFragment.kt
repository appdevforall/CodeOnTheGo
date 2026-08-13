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

package com.itsaky.androidide.fragments

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_TOP
import com.itsaky.androidide.preferences.IPreference
import com.itsaky.androidide.preferences.IPreferenceGroup
import com.itsaky.androidide.preferences.IPreferenceScreen
import com.itsaky.androidide.utils.onLongPress

class IDEPreferencesFragment : BasePreferenceFragment() {
	private var children: List<IPreference> = emptyList()

	/** Every preference in this screen, including nested categories' children, keyed by its key. */
	private var tooltipTagsByKey: Map<String, String> = emptyMap()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
		reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
		exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
		return super.onCreateView(inflater, container, savedInstanceState)
	}

	override fun onCreatePreferences(
		savedInstanceState: Bundle?,
		rootKey: String?,
	) {
		super.onCreatePreferences(savedInstanceState, rootKey)

		if (context == null) {
			return
		}

		@Suppress("DEPRECATION")
		this.children = arguments?.getParcelableArrayList(EXTRA_CHILDREN) ?: emptyList()
		this.tooltipTagsByKey = collectTooltipTags(this.children)

		preferenceScreen.removeAll()
		addChildren(this.children, preferenceScreen)
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		listView.onLongPress { e ->
			val row = listView.findChildViewUnder(e.x, e.y) ?: return@onLongPress
			val position = listView.getChildAdapterPosition(row)
			if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
				return@onLongPress
			}

			val key = (listView.adapter as? PreferenceGroupAdapter)?.getItem(position)?.key ?: return@onLongPress
			val tag = tooltipTagsByKey[key]?.takeIf { it.isNotEmpty() } ?: PREFS_TOP

			row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
			TooltipManager.showIdeCategoryTooltip(requireContext(), row, tag)
		}
	}

	internal fun collectTooltipTags(children: List<IPreference>): Map<String, String> {
		val map = mutableMapOf<String, String>()

		fun visit(items: List<IPreference>) {
			for (item in items) {
				map[item.key] = item.tooltipTag
				if (item is IPreferenceGroup && item !is IPreferenceScreen) {
					visit(item.children)
				}
			}
		}

		visit(children)
		return map
	}

	private fun addChildren(
		children: List<IPreference>,
		pref: PreferenceGroup,
	) {
		for (child in children) {
			val preference = child.onCreateView(requireContext())
			if (child is IPreferenceScreen) {
				preference.fragment = IDEPreferencesFragment::class.java.name
				preference.extras.putParcelableArrayList(EXTRA_CHILDREN, ArrayList(child.children))

				pref.addPreference(preference)
				continue
			}

			if (child is IPreferenceGroup) {
				pref.addPreference(preference as PreferenceCategory)
				addChildren(child.children, preference)
				continue
			}

			pref.addPreference(preference)
		}
	}

	companion object {
		const val EXTRA_CHILDREN = "ide.preferences.fragment.children"
	}
}
