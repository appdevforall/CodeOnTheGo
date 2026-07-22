package com.itsaky.androidide.quickbuild

import android.content.Context
import android.content.SharedPreferences
import org.appdevforall.cotg.quickbuild.service.QuickBuildModeStore

/**
 * SharedPreferences-backed [QuickBuildModeStore]: per-project Quick Build mode state
 * (same-app-id design contract, section 1 - CoGo project preferences, never the
 * user's gradle files). Keys are namespaced by the open project's path, so the toggle
 * and the episode's pin follow the project, not the process. With no project open,
 * reads report mode off and writes are dropped.
 */
class PreferencesQuickBuildModeStore(
	context: Context,
	/** The open project's directory path, or null/blank when none is open. */
	private val projectPath: () -> String?,
) : QuickBuildModeStore {
	private val prefs: SharedPreferences =
		context.getSharedPreferences("quick_build_mode", Context.MODE_PRIVATE)

	override fun isSameAppIdEnabled(): Boolean = key(KEY_ENABLED)?.let { prefs.getBoolean(it, false) } == true

	override fun setSameAppIdEnabled(enabled: Boolean) {
		key(KEY_ENABLED)?.let { prefs.edit().putBoolean(it, enabled).apply() }
	}

	override fun pinnedVersionCode(): Int? =
		key(KEY_VERSION_CODE)?.let { k ->
			prefs.getInt(k, NO_VERSION_CODE).takeIf { it != NO_VERSION_CODE }
		}

	override fun setPinnedVersionCode(versionCode: Int?) {
		key(KEY_VERSION_CODE)?.let { k ->
			prefs.edit().putInt(k, versionCode ?: NO_VERSION_CODE).apply()
		}
	}

	override fun isClobberConfirmed(): Boolean = key(KEY_CONFIRMED)?.let { prefs.getBoolean(it, false) } == true

	override fun setClobberConfirmed(confirmed: Boolean) {
		key(KEY_CONFIRMED)?.let { prefs.edit().putBoolean(it, confirmed).apply() }
	}

	override fun episodeRealApplicationId(): String? = key(KEY_REAL_APP_ID)?.let { prefs.getString(it, null) }

	override fun setEpisodeRealApplicationId(applicationId: String?) {
		key(KEY_REAL_APP_ID)?.let { prefs.edit().putString(it, applicationId).apply() }
	}

	override fun isRestoreDowngradePending(): Boolean = key(KEY_RESTORE_DOWNGRADE)?.let { prefs.getBoolean(it, false) } == true

	override fun setRestoreDowngradePending(pending: Boolean) {
		key(KEY_RESTORE_DOWNGRADE)?.let { prefs.edit().putBoolean(it, pending).apply() }
	}

	override fun hasUsedQuickBuild(): Boolean = key(KEY_HAS_USED)?.let { prefs.getBoolean(it, false) } == true

	override fun setHasUsedQuickBuild(used: Boolean) {
		key(KEY_HAS_USED)?.let { prefs.edit().putBoolean(it, used).apply() }
	}

	private fun key(suffix: String): String? {
		val path = projectPath()?.takeIf { it.isNotBlank() } ?: return null
		return "$path::$suffix"
	}

	private companion object {
		private const val KEY_ENABLED = "sameAppId"
		private const val KEY_VERSION_CODE = "pinnedVersionCode"
		private const val KEY_CONFIRMED = "clobberConfirmed"
		private const val KEY_REAL_APP_ID = "episodeRealApplicationId"
		private const val KEY_RESTORE_DOWNGRADE = "restoreDowngradePending"
		private const val KEY_HAS_USED = "hasUsedQuickBuild"

		/** Sentinel for "no pin"; versionCodes are always positive. */
		private const val NO_VERSION_CODE = 0
	}
}
