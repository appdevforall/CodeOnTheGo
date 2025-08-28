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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILD_RUN
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_EDITOR
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_GENERAL
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_TERMUX
import com.itsaky.androidide.preferences.IPreference
import com.itsaky.androidide.preferences.IPreferenceGroup
import com.itsaky.androidide.preferences.IPreferenceScreen
import kotlinx.coroutines.launch
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY

class IDEPreferencesFragment : BasePreferenceFragment() {

  private var children: List<IPreference> = emptyList()

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
    reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)

    if (context == null) {
      return
    }

    @Suppress("DEPRECATION")
    this.children = arguments?.getParcelableArrayList(EXTRA_CHILDREN) ?: emptyList()

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  private fun addChildren(children: List<IPreference>, pref: PreferenceGroup) {
    for (child in children) {
      val preference = child.onCreateView(requireContext())
      if (child is IPreferenceScreen) {
        preference.fragment = IDEPreferencesFragment::class.java.name
        preference.extras.putParcelableArrayList(EXTRA_CHILDREN, ArrayList(child.children))

        // Wrap target preferences with long-click support
        val finalPreference = if (isTargetPreference(child.key)) {
          createLongClickablePreference(preference, child.key)
        } else {
          preference
        }

        pref.addPreference(finalPreference)
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

  private fun isTargetPreference(key: String): Boolean {
    return key in listOf(
      "idepref_general",
      "idepref_editor",
      "idepref_build_n_run",
      "ide.preferences.terminal"
    )
  }

  private fun createLongClickablePreference(originalPreference: Preference, key: String): Preference {
    return object : Preference(requireContext()) {
      init {
        // Copy essential properties from original preference
        this.key = originalPreference.key
        title = originalPreference.title
        summary = originalPreference.summary
        fragment = originalPreference.fragment
        extras.putAll(originalPreference.extras)
        
        // Match the original preference's icon space settings
        icon = originalPreference.icon
        isIconSpaceReserved = originalPreference.isIconSpaceReserved
      }

      override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Set up long click listener on the view
        holder.itemView.setOnLongClickListener { view ->
          val tooltipTag = getTooltipTag(key)
          if (tooltipTag != null) {
            showTooltip(view, tooltipTag)
          } else {
            Log.d("IDEPreferencesFragment", "Long click detected on preference: $key")
          }
          true
        }
      }
    }
  }

  private fun getTooltipTag(key: String): String? {
    return when (key) {
      "idepref_general" -> PREFS_GENERAL
      "idepref_editor" -> PREFS_EDITOR
      "idepref_build_n_run" -> PREFS_BUILD_RUN
      "ide.preferences.terminal" -> PREFS_TERMUX
      else -> null
    }
  }

  private fun showTooltip(anchorView: View, tooltipTag: String) {
    lifecycleScope.launch {
      try {
        val tooltipData = TooltipManager.getTooltip(
          context = requireContext(),
          category = TooltipCategory.CATEGORY_IDE,
          tag = tooltipTag
        )

        tooltipData?.let { data ->
          TooltipManager.showIDETooltip(
            context = requireContext(),
            anchorView = anchorView,
            level = 0,
            tooltipItem = IDETooltipItem(
              rowId = data.rowId,
              id = data.id,
              category = TooltipCategory.CATEGORY_IDE,
              tag = data.tag,
              summary = data.summary,
              detail = data.detail,
              buttons = data.buttons,
              lastChange = data.lastChange
            )
          ) { context, url, title ->
            val intent = Intent(context, HelpActivity::class.java).apply {
              putExtra(CONTENT_KEY, url)
              putExtra(CONTENT_TITLE_KEY, title)
            }
            context.startActivity(intent)
          }
        }
      } catch (e: Exception) {
        Log.e("IDEPreferencesFragment", "Error showing tooltip for tag: $tooltipTag", e)
      }
    }
  }

  companion object {
    const val EXTRA_CHILDREN = "ide.preferences.fragment.children"
  }
}

