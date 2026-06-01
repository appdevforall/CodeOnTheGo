

package com.itsaky.androidide.plugins.manager.services

import android.app.Activity
import android.content.Intent
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.services.IdeUIService

/**
 * Implementation of IdeUIService that provides access to Code On the Go's UI context
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

    override fun openPluginScreen(
        pluginId: String,
        fragmentClassName: String,
        title: String?
    ): Boolean {
        val activity = getCurrentActivity() ?: return false
        val intent = Intent(IdeUIService.ACTION_OPEN_PLUGIN_SCREEN).apply {
            setPackage(activity.packageName)
            putExtra(IdeUIService.EXTRA_PLUGIN_ID, pluginId)
            putExtra(IdeUIService.EXTRA_FRAGMENT_CLASS_NAME, fragmentClassName)
            putExtra(IdeUIService.EXTRA_TITLE, title)
        }
        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
