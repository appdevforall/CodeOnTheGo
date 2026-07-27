package com.itsaky.androidide.actions

import android.content.Context
import android.util.Log
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.manager.pluginCategory
import com.itsaky.androidide.plugins.manager.pluginTooltipTag
import com.itsaky.androidide.plugins.manager.ui.PluginDrawableResolver

/**
 * Adapts a plugin-contributed [ToolbarAction] (from `UIExtension.getToolbarActions()`)
 * into an editor-toolbar [ActionItem].
 *
 * Unlike [PluginActionItem] (which wraps `getMainMenuItems()`), this is the dedicated
 * path for toolbar icons: it carries the action's own [ToolbarAction.order] so a plugin
 * can position its icon among the built-in actions, and it opts into [honorVisibility]
 * so the toolbar fully removes it (instead of greying it) when not applicable.
 */
class PluginToolbarActionItem(
    private val context: Context,
    private val toolbarAction: ToolbarAction,
    val pluginId: String
) : EditorActivityAction() {

    override val id: String = "plugin.toolbar.${toolbarAction.id}"

    override val order: Int = toolbarAction.order

    override val honorVisibility: Boolean get() = true

    init {
        label = toolbarAction.title
        val iconResId = toolbarAction.icon
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
        if (!visible) {
            // EditorActivityAction.prepare() hides the action when the editor context is
            // missing; respect that and skip the plugin providers.
            return
        }
        runCatching {
            enabled = toolbarAction.isEnabledProvider?.invoke() ?: toolbarAction.isEnabled
            visible = toolbarAction.isVisibleProvider?.invoke() ?: toolbarAction.isVisible
            // Re-resolve the icon on every rebuild when the plugin drives it dynamically.
            // When iconProvider is null the icon stays as resolved in init (static behavior).
            if (visible) {
                toolbarAction.iconProvider?.invoke()?.let { resId ->
                    PluginDrawableResolver.resolve(resId, pluginId, context)?.let { icon = it }
                }
            }
        }.onFailure { e ->
            // A throwing/disposed plugin must never keep a stale icon on the toolbar.
            Log.w("PluginToolbarActionItem", "prepare failed for '${toolbarAction.id}'", e)
            enabled = false
            visible = false
        }
    }

    override fun getShowAsActionFlags(data: ActionData): Int = when (toolbarAction.showAsAction) {
        ShowAsAction.ALWAYS -> MenuItem.SHOW_AS_ACTION_ALWAYS
        ShowAsAction.IF_ROOM -> MenuItem.SHOW_AS_ACTION_IF_ROOM
        ShowAsAction.NEVER -> MenuItem.SHOW_AS_ACTION_NEVER
        ShowAsAction.WITH_TEXT -> MenuItem.SHOW_AS_ACTION_WITH_TEXT
        ShowAsAction.COLLAPSE_ACTION_VIEW -> MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
    }

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String =
        pluginTooltipTag(pluginId, toolbarAction.id)

    override fun retrieveTooltipCategory(): String = pluginCategory(pluginId)

    override suspend fun execAction(data: ActionData): Any {
        return try {
            toolbarAction.action.invoke()
            true
        } catch (e: Exception) {
            Log.e("PluginToolbarActionItem", "Error executing toolbar action '${toolbarAction.id}'", e)
            false
        }
    }
}
