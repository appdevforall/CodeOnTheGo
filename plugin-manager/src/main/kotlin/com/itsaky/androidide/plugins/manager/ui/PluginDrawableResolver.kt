package com.itsaky.androidide.plugins.manager.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

object PluginDrawableResolver {

    fun resolve(resId: Int, pluginId: String?, fallbackContext: Context): Drawable? {
        if (pluginId != null) {
            val pluginContext = PluginFragmentHelper.getPluginContext(pluginId)
            if (pluginContext == null) {
                return loadDrawable(fallbackContext, resId)
            }
            try {
                val drawable = ContextCompat.getDrawable(pluginContext, resId)
                return drawable
            } catch (_: Resources.NotFoundException) {
            } catch (_: Throwable) {
            }
        }
        return loadDrawable(fallbackContext, resId)
    }

    private fun loadDrawable(context: Context, resId: Int): Drawable? =
        try { ContextCompat.getDrawable(context, resId) } catch (_: Resources.NotFoundException) { null }
}
