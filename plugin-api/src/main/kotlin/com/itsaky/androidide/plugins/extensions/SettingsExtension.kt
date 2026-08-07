package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin

/**
 * Interface for plugins that contribute rows to the IDE Preferences screen.
 *
 * Each entry becomes one preference row in the IDE's Configuration group, alongside Terminal,
 * Git and Plugin Manager. Tapping it mounts [PluginSettingsEntry.fragmentClassName] full-screen
 * on an IDE-owned container.
 *
 * The IDE knows nothing about what a plugin's settings screen contains: the plugin owns both the
 * UI and the persistence (see `PluginContext.getPluginSharedPreferences`). The IDE never renders
 * widgets for, reads, or writes a plugin's setting values.
 */
interface SettingsExtension : IPlugin {
	/**
	 * Provide the settings entries to show in the IDE Preferences screen.
	 * @return List of entries, one preference row each.
	 */
	fun getSettingsEntries(): List<PluginSettingsEntry> = emptyList()
}

/**
 * One IDE Preferences row owned by a plugin.
 *
 * The hosted fragment is shown full-screen with no IDE toolbar and no IDE back affordance, so
 * supply your own app bar and your own way back (`requireActivity().finish()`) - exactly as the
 * layout-editor plugin screens do.
 *
 * @property id Stable id, unique within the plugin. Forms part of the row's preference key.
 * @property title Row title. A runtime string, NOT an IDE string resource, so localize it yourself.
 * @property summary Optional second line shown under the title.
 * @property fragmentClassName Fully-qualified name of the Fragment to mount. Must be a public
 * class in the plugin with a no-arg constructor, resolvable by the plugin's own classloader.
 * @property order Sort order among all contributed rows; ties break on [title].
 * @property isVisible When false the IDE skips the row entirely.
 */
data class PluginSettingsEntry(
	val id: String,
	val title: String,
	val summary: String? = null,
	val fragmentClassName: String,
	val order: Int = 0,
	val isVisible: Boolean = true,
)
