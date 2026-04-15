package com.itsaky.androidide.plugins.manager.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.ContextCompat
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

object PluginDrawableResolver {

    fun resolve(resId: Int, pluginId: String?, fallbackContext: Context): Drawable? {
        if (pluginId != null) {
            val pluginContext = PluginFragmentHelper.getPluginContext(pluginId)
            if (pluginContext == null) {
                return loadDrawable(fallbackContext, resId)
            }
            val drawable = runCatching {
                ContextCompat.getDrawable(pluginContext, resId)
            }.onFailure { e ->
                if (e !is Resources.NotFoundException && e !is IllegalArgumentException) {
                    Log.w("PluginDrawableResolver", "Failed to resolve drawable $resId for plugin $pluginId", e)
                }
            }.getOrNull()
            if (drawable != null) return drawable
        }
        return loadDrawable(fallbackContext, resId)
    }

    private fun loadDrawable(context: Context, resId: Int): Drawable? =
        try { ContextCompat.getDrawable(context, resId) } catch (_: Resources.NotFoundException) { null }
}
