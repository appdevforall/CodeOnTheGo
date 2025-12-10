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

import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.parcelize.Parcelize

internal fun IDEPreferences.addRootPreferences() {
  addPreference(ConfigurationPreferences())
  addPreference(DeveloperOptionsScreen())
}

@Parcelize
class ConfigurationPreferences(
  override val key: String = "idepref_configure",
  override val title: Int = string.configure,
  override val children: List<IPreference> = mutableListOf()
) : IPreferenceGroup() {

  init {
    addPreference(GeneralPreferencesScreen())
    addPreference(EditorPreferencesScreen())
    addPreference(BuildAndRunPreferences())
    addPreference(TermuxPreferences())

    // Only show Plugin Manager when experimental features are enabled
    if (FeatureFlags.isExperimentsEnabled) {
      addPreference(PluginManagerEntry())
    }
    
    addPreference(about)
  }
}


