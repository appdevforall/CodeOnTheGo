package com.itsaky.androidide.activities

import android.content.Context
import org.slf4j.LoggerFactory

/**
 * Launches a plugin-owned screen in [PluginScreenActivity].
 *
 * Kept out of the callers so that "how a plugin screen is opened" lives in one place: today the
 * contributed preference rows, later any other surface that wants to mount plugin UI. Callers decide
 * what the user sees when it fails - the message belongs to the surface, not to the launch.
 */
object PluginScreenNavigator {
	private val log = LoggerFactory.getLogger("PluginScreenNavigator")

	/**
	 * Opens [fragmentClassName] of the plugin [pluginId], full-screen.
	 *
	 * @return true when the screen was launched; false (already logged) when it could not be, so the
	 * caller can tell the user.
	 */
	fun openPluginScreen(
		context: Context,
		pluginId: String,
		fragmentClassName: String,
		title: String? = null,
	): Boolean =
		try {
			context.startActivity(
				PluginScreenActivity.newIntent(context, pluginId, fragmentClassName, title),
			)
			true
		} catch (error: Exception) {
			log.error("Failed to open screen {} of plugin {}", fragmentClassName, pluginId, error)
			false
		}
}
