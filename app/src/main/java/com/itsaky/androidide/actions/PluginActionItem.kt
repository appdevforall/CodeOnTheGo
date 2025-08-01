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

package com.itsaky.androidide.actions

import android.content.Context
import android.graphics.drawable.Drawable
import com.itsaky.androidide.plugins.extensions.MenuItem


class PluginActionItem(
    context: Context,
    private val menuItem: MenuItem,
    override val order: Int
) : EditorActivityAction() {

    override val id: String = "plugin.${menuItem.id}"

    init {
        label = menuItem.title
        icon = null
        // Plugin menu items appear in the toolbar
        location = ActionItem.Location.EDITOR_TOOLBAR
        requiresUIThread = true
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)
        enabled = menuItem.isEnabled
        visible = menuItem.isVisible
    }

    override suspend fun execAction(data: ActionData): Any {
        return try {
            // Execute the plugin's action callback on UI thread
            menuItem.action.invoke()
            true
        } catch (e: Exception) {
            // Log error but don't crash the app
            android.util.Log.e("PluginActionItem", "Error executing plugin action '${menuItem.id}': ${e.message}", e)
            false
        }
    }
}