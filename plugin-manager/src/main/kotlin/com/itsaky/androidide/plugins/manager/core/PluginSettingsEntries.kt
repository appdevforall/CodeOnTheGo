package com.itsaky.androidide.plugins.manager.core

import com.itsaky.androidide.plugins.extensions.PluginSettingsEntry
import com.itsaky.androidide.plugins.extensions.SettingsExtension

/**
 * Collects the preference rows contributed by [extensions], each paired with its owning plugin id.
 *
 * A plugin that throws from [SettingsExtension.getSettingsEntries] contributes nothing and is
 * reported through [onError]; every other plugin's rows still reach the Preferences screen.
 *
 * The order is a total one - plugin id and entry id break a title tie - so repeated calls over an
 * unchanged plugin set return an equal list. The caller relies on that: `PreferencesActivity`
 * compares successive results to decide whether to rebuild, and the iteration order of the
 * underlying plugin map is not stable across mutations.
 *
 * Lives outside [PluginManager] so the collection contract - error isolation, visibility filter,
 * ordering - is unit-testable without a loaded plugin set behind a Context-bound singleton.
 */
internal fun collectPluginSettingsEntries(
	extensions: List<Pair<String, SettingsExtension>>,
	onError: (pluginId: String, error: Throwable) -> Unit,
): List<Pair<String, PluginSettingsEntry>> =
	extensions
		.flatMap { (pluginId, extension) ->
			runCatching { extension.getSettingsEntries() }
				.onFailure { error -> onError(pluginId, error) }
				.getOrDefault(emptyList())
				.filter { it.isVisible }
				.map { entry -> pluginId to entry }
		}.sortedWith(
			compareBy({ it.second.order }, { it.second.title }, { it.first }, { it.second.id }),
		)
