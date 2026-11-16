package com.itsaky.androidide.plugins.manager.core

/**
 * Fails when [id] can't be a plugin ID. The ID names the plugin's files and folders, so one that
 * is a path reaches outside them: installing `../files` would delete the app's whole files folder.
 * Nullable because a `plugin.json` manifest without an ID parses to a null one.
 */
internal fun checkPluginId(id: String?): Result<String> =
	if (id.isNullOrEmpty() || id == "." || id == ".." || id.any { it == '/' || it == '\\' || it == '\u0000' }) {
		Result.failure(IllegalArgumentException("Invalid plugin ID \"$id\": it must be a name, not a path"))
	} else {
		Result.success(id)
	}
