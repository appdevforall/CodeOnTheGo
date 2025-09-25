package com.itsaky.androidide.plugins.manager.services

import android.content.Context
import android.view.View
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.plugins.services.IdeTooltipService

/**
 * Implementation of the tooltip service for plugins.
 * Provides a clean API for plugins to show tooltips.
 */
class IdeTooltipServiceImpl(
    private val context: Context
) : IdeTooltipService {

    private val themedContext: Context by lazy {
        // Wrap the context with a proper Material theme to ensure tooltip inflation works
        try {
            androidx.appcompat.view.ContextThemeWrapper(
                context,
                com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight
            )
        } catch (e: Exception) {
            // Fallback to regular Material theme if dynamic colors not available
            try {
                androidx.appcompat.view.ContextThemeWrapper(
                    context,
                    com.google.android.material.R.style.Theme_Material3_DayNight
                )
            } catch (e: Exception) {
                context
            }
        }
    }

    companion object {
        private const val LOG_TAG = "IdeTooltipService"
    }

    override fun showTooltip(anchorView: View, category: String, tag: String) {
        try {
            // Use the themed context to ensure proper attribute resolution
            TooltipManager.showTooltip(
                context = themedContext,
                anchorView = anchorView,
                category = category,
                tag = tag
            )
        } catch (e: android.view.InflateException) {
            android.util.Log.e(LOG_TAG, "Failed to inflate tooltip layout: $category.$tag", e)
        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "Failed to show tooltip: $category.$tag", e)
        }
    }

    override fun showTooltip(anchorView: View, tag: String) {
        try {
            // Use the themed context to ensure proper attribute resolution
            TooltipManager.showTooltip(
                context = themedContext,
                anchorView = anchorView,
                tag = tag
            )
        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "Failed to show tooltip: $tag", e)
        }
    }

}