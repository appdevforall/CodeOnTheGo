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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_TOP
import com.itsaky.androidide.preferences.IPreference
import com.itsaky.androidide.preferences.IPreferenceGroup
import com.itsaky.androidide.preferences.IPreferenceScreen
import com.itsaky.androidide.utils.onLongPress

class IDEPreferencesFragment : BasePreferenceFragment() {
	/** Every preference in this screen, including nested categories' children, keyed by its key. */
	internal var tooltipTagsByKey: Map<String, String> = emptyMap()

	/**
	 * This screen's own tag - the fallback for a long-press that lands on empty RecyclerView
	 * space (no row under the touch point) or on a row with no tooltipTag of its own. Also read
	 * by [com.itsaky.androidide.activities.PreferencesActivity] to resolve the toolbar's and the
	 * scroll container's long-press tooltip to whichever screen is currently showing.
	 */
	internal var screenTooltipTag: String = PREFS_TOP
		private set

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

		@Suppress("DEPRECATION")
		val children: List<IPreference> = arguments?.getParcelableArrayList(EXTRA_CHILDREN) ?: emptyList()
		this.tooltipTagsByKey = collectTooltipTags(children)
		this.screenTooltipTag = resolveScreenTooltipTag(arguments?.getString(EXTRA_SCREEN_TOOLTIP_TAG))

		// Neither read above needs a context; only building the actual preference UI does.
		if (context == null) {
			return
		}

		preferenceScreen.removeAll()
		addChildren(children, preferenceScreen)
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		// Captured once: re-reading the `listView` property from inside the callback (which can
		// fire after a delay) would hit a field PreferenceFragmentCompat clears in onDestroyView.
		val recyclerView = listView

		recyclerView.onLongPress { e ->
			if (!isAdded) {
				return@onLongPress
			}
			val ctx = context ?: return@onLongPress

			val row = recyclerView.findChildViewUnder(e.x, e.y)
			val tag = row?.let { resolveTooltipTag(recyclerView, it) } ?: screenTooltipTag
			val anchor = row ?: recyclerView

			anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
			TooltipManager.showIdeCategoryTooltip(ctx, anchor, tag)
		}
	}

	/** [EXTRA_SCREEN_TOOLTIP_TAG]'s value, or [PREFS_TOP] if it's missing or blank. */
	internal fun resolveScreenTooltipTag(rawTag: String?): String = rawTag?.takeIf { it.isNotBlank() } ?: PREFS_TOP

	/** The row's own tooltipTag, or null if there's no row at that position or it has none. */
	internal fun resolveTooltipTag(
		recyclerView: RecyclerView,
		row: View,
	): String? {
		val position = recyclerView.getChildAdapterPosition(row)
		if (position == RecyclerView.NO_POSITION) {
			return null
		}

		val key = (recyclerView.adapter as? PreferenceGroupAdapter)?.getItem(position)?.key ?: return null
		return tooltipTagsByKey[key]?.takeIf { it.isNotEmpty() }
	}

	/**
	 * Recursively walks [children], mapping each preference key to its tooltip tag.
	 * Nested [IPreferenceScreen] nodes are not descended into. Entries with an empty
	 * tooltip tag are kept as-is. Throws if two preferences in the tree share a key.
	 */
	internal fun collectTooltipTags(children: List<IPreference>): Map<String, String> {
		val map = mutableMapOf<String, String>()

		fun visit(items: List<IPreference>) {
			for (item in items) {
				check(item.key !in map) { "Duplicate preference key in this screen's tree: ${item.key}" }
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
				preference.extras.putString(EXTRA_SCREEN_TOOLTIP_TAG, child.tooltipTag)

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
		const val EXTRA_SCREEN_TOOLTIP_TAG = "ide.preferences.fragment.screenTooltipTag"
	}
}
