package com.itsaky.androidide.plugins.base

import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import com.itsaky.androidide.plugins.ServiceRegistry
import java.lang.ref.WeakReference

/**
 * Base class for plugin fragments that ensures proper resource context.
 * This is a simplified implementation to avoid androidx Fragment annotation issues.
 *
 * Plugin fragments should extend android.app.Fragment or androidx.fragment.app.Fragment
 * and manually implement resource context handling using the static helper methods.
 */
object PluginFragmentHelper {

    private val pluginContexts = mutableMapOf<String, Context>()
    private val serviceRegistries = mutableMapOf<String, ServiceRegistry>()
    private val legacyPlugins = mutableSetOf<String>()

    private data class ActivitySnapshot(
        val contextRef: WeakReference<Context>,
        val themeRef: WeakReference<Resources.Theme>
    )

    private val activitySnapshots = mutableMapOf<String, ActivitySnapshot>()

    @JvmStatic
    fun getCurrentActivityTheme(pluginId: String): Resources.Theme? =
        activitySnapshots[pluginId]?.themeRef?.get()

    @JvmStatic
    fun getCurrentActivityContext(pluginId: String): Context? =
        activitySnapshots[pluginId]?.contextRef?.get()

    /**
     * Register a plugin's resource context.
     * Called by the plugin manager when loading APK-based plugins.
     */
    @JvmStatic
    @JvmOverloads
    fun registerPluginContext(pluginId: String, context: Context, isLegacy: Boolean = false) {
        pluginContexts[pluginId] = context
        if (isLegacy) legacyPlugins.add(pluginId) else legacyPlugins.remove(pluginId)
    }

    /**
     * Unregister a plugin's resource context.
     * Called when a plugin is unloaded.
     */
    @JvmStatic
    fun unregisterPluginContext(pluginId: String) {
        pluginContexts.remove(pluginId)
        activitySnapshots.remove(pluginId)
        legacyPlugins.remove(pluginId)
    }

    /**
     * Get the resource context for a specific plugin.
     */
    @JvmStatic
    fun getPluginContext(pluginId: String): Context? {
        return pluginContexts[pluginId]
    }

    /**
     * Register a plugin's service registry.
     * Called by the plugin manager when creating plugin context.
     */
    @JvmStatic
    fun registerServiceRegistry(pluginId: String, registry: ServiceRegistry) {
        serviceRegistries[pluginId] = registry
    }

    /**
     * Get the service registry for a specific plugin.
     */
    @JvmStatic
    fun getServiceRegistry(pluginId: String): ServiceRegistry? {
        return serviceRegistries[pluginId]
    }

    /**
     * Clear all registered plugin contexts.
     */
    @JvmStatic
    fun clearAllContexts() {
        pluginContexts.clear()
        serviceRegistries.clear()
        activitySnapshots.clear()
        legacyPlugins.clear()
    }

    /**
     * Create a LayoutInflater with the plugin's resource context.
     * Fragments should call this in their onGetLayoutInflater() method.
     *
     * @param pluginId The plugin ID
     * @param defaultInflater The default inflater from the fragment
     * @return An inflater with the plugin's context, or the default if not found
     */
    @JvmStatic
    fun getPluginInflater(pluginId: String, defaultInflater: LayoutInflater): LayoutInflater {
        val pluginContext = getPluginContext(pluginId) ?: return defaultInflater
        activitySnapshots[pluginId] = ActivitySnapshot(
            contextRef = WeakReference(defaultInflater.context),
            themeRef = WeakReference(defaultInflater.context.theme)
        )
        if (pluginId in legacyPlugins) {
            return pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as? LayoutInflater
                ?: defaultInflater.cloneInContext(pluginContext)
        }
        return defaultInflater.cloneInContext(pluginContext)
    }
}