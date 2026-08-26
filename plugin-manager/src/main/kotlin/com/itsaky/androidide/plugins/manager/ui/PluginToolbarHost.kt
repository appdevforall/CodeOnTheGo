package com.itsaky.androidide.plugins.manager.ui

/**
 * Implemented by the editor Activity so lower modules (e.g. the plugin services in
 * `plugin-manager`) can ask it to rebuild the plugin toolbar without depending on the
 * `app` module. Bridges the reverse dependency: `app` depends on `plugin-manager`, so
 * the Activity implements this interface and the service casts to it.
 */
interface PluginToolbarHost {

    /**
     * Rebuilds the editor toolbar, re-evaluating every plugin toolbar action's dynamic
     * providers (icon/enabled/visible). Implementations must ensure the work runs on the
     * UI thread.
     */
    fun refreshPluginToolbarActions()
}
