

package com.itsaky.androidide.actions

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.manager.pluginCategory
import com.itsaky.androidide.plugins.manager.pluginTooltipTag
import com.itsaky.androidide.plugins.manager.ui.PluginDrawableResolver


class PluginActionItem(
    context: Context,
    private val menuItem: MenuItem,
    override val order: Int,
    val pluginId: String
) : EditorActivityAction() {

    override val id: String = "plugin.${menuItem.id}"

    init {
        label = menuItem.title
            val iconResId = menuItem.icon
        icon = if (iconResId != null) {
            PluginDrawableResolver.resolve(iconResId, pluginId, context)
                ?: ContextCompat.getDrawable(context, R.drawable.ic_package)
        } else {
            ContextCompat.getDrawable(context, R.drawable.ic_package)
        }
        location = ActionItem.Location.EDITOR_TOOLBAR
        requiresUIThread = true
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)
        enabled = menuItem.isEnabledProvider?.invoke() ?: menuItem.isEnabled
        visible = menuItem.isVisibleProvider?.invoke() ?: menuItem.isVisible
    }

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String =
        menuItem.tooltipTag ?: pluginTooltipTag(pluginId, menuItem.id)

    override fun retrieveTooltipCategory(): String = pluginCategory(pluginId)

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
