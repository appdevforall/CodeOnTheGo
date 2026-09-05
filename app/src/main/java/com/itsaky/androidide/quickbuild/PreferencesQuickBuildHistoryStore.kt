package com.itsaky.androidide.quickbuild

import android.content.Context
import android.content.SharedPreferences
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildHistoryStore

/**
 * SharedPreferences-backed [QuickBuildHistoryStore]: per-project Quick Build history in CoGo's
 * project preferences (never the user's gradle files). The key is namespaced by the open
 * project's path, so "has this project used Quick Build" follows the project, not the process.
 * With no project open, reads report false and writes are dropped.
 */
class PreferencesQuickBuildHistoryStore(
	context: Context,
	/** The open project's directory path, or null/blank when none is open. */
	private val projectPath: () -> String?,
) : QuickBuildHistoryStore {
	private val prefs: SharedPreferences =
		context.getSharedPreferences("quick_build_mode", Context.MODE_PRIVATE)

	override fun hasUsedQuickBuild(): Boolean = key(KEY_HAS_USED)?.let { prefs.getBoolean(it, false) } == true

	override fun setHasUsedQuickBuild(used: Boolean) {
		key(KEY_HAS_USED)?.let { prefs.edit().putBoolean(it, used).apply() }
	}

	private fun key(suffix: String): String? {
		val path = projectPath()?.takeIf { it.isNotBlank() } ?: return null
		return "$path::$suffix"
	}

	private companion object {
		private const val KEY_HAS_USED = "hasUsedQuickBuild"
	}
}
