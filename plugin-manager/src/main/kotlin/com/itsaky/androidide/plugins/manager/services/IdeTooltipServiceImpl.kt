package com.itsaky.androidide.plugins.manager.services

import android.content.Context
import android.view.View
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.manager.pluginCategory
import com.itsaky.androidide.plugins.services.IdeTooltipService

/**
 * Implementation of the tooltip service for plugins.
 * Delegates to the main TooltipManager, using "plugin_<pluginId>" as the category
 * so plugin entries are stored alongside built-in documentation in documentation.db
 * without conflicting with them.
 */
class IdeTooltipServiceImpl(
    private val context: Context,
    private val pluginId: String,
    private val activityProvider: PluginManager.ActivityProvider?
) : IdeTooltipService {

    private val pluginCategory = pluginCategory(pluginId)

    /**
     * Returns a context suitable for inflating the tooltip layout.
     * Prefers the live Activity (correct Material3 theme + dark mode configuration).
     * Falls back to the app context.
     */
    private fun resolvedContext(): Context {
        activityProvider?.getCurrentActivity()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) return activity
        }
        return context
    }

    override fun showTooltip(anchorView: View, category: String, tag: String) {
        try {
            TooltipManager.showTooltip(resolvedContext(), anchorView, pluginCategory, tag)
        } catch (e: android.view.InflateException) {
            android.util.Log.e("IdeTooltipService", "Failed to inflate tooltip layout: $pluginCategory.$tag", e)
        } catch (e: Exception) {
            android.util.Log.e("IdeTooltipService", "Failed to show tooltip: $pluginCategory.$tag", e)
        }
    }

    override fun showTooltip(anchorView: View, tag: String) {
        try {
            TooltipManager.showTooltip(resolvedContext(), anchorView, pluginCategory, tag)
        } catch (e: Exception) {
            android.util.Log.e("IdeTooltipService", "Failed to show tooltip: $pluginCategory.$tag", e)
        }
    }
}
