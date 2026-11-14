package com.itsaky.androidide.repositories

/**
 * Installing [pluginId] found its installed version still loaded after uninstalling it, so the new
 * file was not put in place: loading skips a plugin that is already loaded. Shown to the user as
 * `msg_plugin_install_still_loaded`.
 */
class PluginStillLoadedException(
	val pluginId: String,
) : IllegalStateException("The installed version of $pluginId could not be unloaded")
