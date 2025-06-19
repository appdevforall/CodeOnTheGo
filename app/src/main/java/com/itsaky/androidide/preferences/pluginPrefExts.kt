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
import android.content.Intent
import androidx.preference.Preference
import com.itsaky.androidide.activities.PluginManagerActivity
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import kotlinx.parcelize.Parcelize

@Parcelize
class PluginManagerEntry(
  override val key: String = "idepref_plugin_manager",
  override val title: Int = string.plugin_manager_title,
  override val summary: Int? = string.plugin_manager_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context)
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    val intent = Intent(context, PluginManagerActivity::class.java)
    // Add flags to prevent multiple instances
    intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    context.startActivity(intent)
    return true
  }
}