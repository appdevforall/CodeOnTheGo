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

package com.itsaky.androidide.plugins.manager.services

import android.app.Activity
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.manager.PluginManager

/**
 * Implementation of IdeUIService that provides access to AndroidIDE's UI context
 * for plugins that need to show dialogs or perform UI operations.
 */
class IdeUIServiceImpl(
    private val activityProvider: PluginManager.ActivityProvider?
) : IdeUIService {

    override fun getCurrentActivity(): Activity? {
        return try {
            activityProvider?.getCurrentActivity()
        } catch (e: Exception) {
            // Log error but don't expose internal details
            null
        }
    }

    override fun isUIAvailable(): Boolean {
        return getCurrentActivity() != null
    }
}