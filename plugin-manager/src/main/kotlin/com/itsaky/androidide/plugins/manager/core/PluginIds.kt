package com.itsaky.androidide.plugins.manager.core

import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION

/**
 * Fails when [id] can't be a plugin ID. The ID names the plugin's files and folders, so one that
 * is a path reaches outside them: installing `../files` would delete the app's whole files folder.
 * So do two names CoGo uses itself in the plugins folder, where the data folder is `<id>`: `oat`
 * is ART's folder of every plugin's compiled code, and `x.cgp` is plugin `x`'s file from an older
 * CoGo build. Uninstalling either would delete what it names.
 * Nullable because a `plugin.json` manifest without an ID parses to a null one.
 */
internal fun checkPluginId(id: String?): Result<String> =
	when {
		id.isNullOrEmpty() || id == "." || id == ".." || id.any { it == '/' || it == '\\' || it == '\u0000' } -> {
			Result.failure(IllegalArgumentException("Invalid plugin ID \"$id\": it must be a name, not a path"))
		}

		id.equals("oat", ignoreCase = true) -> {
			Result.failure(IllegalArgumentException("Invalid plugin ID \"$id\": \"oat\" is the folder of compiled plugin code"))
		}

		id.endsWith(".$PLUGIN_ARCHIVE_EXTENSION", ignoreCase = true) -> {
			Result.failure(
				IllegalArgumentException(
					"Invalid plugin ID \"$id\": it must not end in .$PLUGIN_ARCHIVE_EXTENSION, which names a plugin file",
				),
			)
		}

		else -> {
			Result.success(id)
		}
	}
