

package com.itsaky.androidide.actions

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.plugins.extensions.MenuItem


class PluginActionItem(
    context: Context,
    private val menuItem: MenuItem,
    override val order: Int
) : EditorActivityAction() {

    override val id: String = "plugin.${menuItem.id}"

    init {
        label = menuItem.title
        icon = ContextCompat.getDrawable(context, R.drawable.ic_package)
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