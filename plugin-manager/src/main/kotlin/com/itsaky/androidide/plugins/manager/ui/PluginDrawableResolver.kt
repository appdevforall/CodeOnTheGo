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
            if (pluginContext != null) {
                try {
                    return ContextCompat.getDrawable(pluginContext, resId)
                } catch (_: Resources.NotFoundException) { }
            }
        }
        return try {
            ContextCompat.getDrawable(fallbackContext, resId)
        } catch (_: Resources.NotFoundException) {
            null
        }
    }
}
