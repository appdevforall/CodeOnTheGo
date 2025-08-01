

package com.itsaky.androidide.plugins.manager.services

import android.app.Activity
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.manager.PluginManager

/**
 * Implementation of IdeUIService that provides access to COGO's UI context
 * for plugins that need to show dialogs or perform UI operations.
 */
class IdeUIServiceImpl(
    private val activityProvider: PluginManager.ActivityProvider?
) : IdeUIService {

    override fun getCurrentActivity(): Activity? {
        return try {
            activityProvider?.getCurrentActivity()
        } catch (e: Exception) {
            null
        }
    }

    override fun isUIAvailable(): Boolean {
        return getCurrentActivity() != null
    }
}