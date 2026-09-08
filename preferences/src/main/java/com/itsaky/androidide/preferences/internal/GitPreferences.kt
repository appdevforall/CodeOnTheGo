package com.itsaky.androidide.preferences.internal

import java.io.File

/**
 * Preferences for Git configuration.
 */
object GitPreferences {
	const val GIT_USER_NAME = "git_user_name"
	const val GIT_USER_EMAIL = "git_user_email"
	const val ADD_GLOBAL_COMMIT_WATERMARK = "add_git_commit_watermark"
	const val PROJECT_WATERMARK_PREFIX = "git_commit_watermark_project"

	var userName: String?
		get() = prefManager.getString(GIT_USER_NAME, null)
		set(value) {
			prefManager.putString(GIT_USER_NAME, value)
		}

	var userEmail: String?
		get() = prefManager.getString(GIT_USER_EMAIL, null)
		set(value) {
			prefManager.putString(GIT_USER_EMAIL, value)
		}

	var shouldAddGlobalCommitWatermark: Boolean
		get() = prefManager.getBoolean(ADD_GLOBAL_COMMIT_WATERMARK, true)
		set(value) {
			prefManager.putBoolean(ADD_GLOBAL_COMMIT_WATERMARK, value)
		}

	fun isProjectWatermarkEnabled(projectPath: String?): Boolean {
		if (projectPath.isNullOrBlank()) {
			return true
		}
		return prefManager.getBoolean(getProjectWatermarkKey(projectPath), true)
	}

	fun enableProjectWatermark(
		projectPath: String?,
		enabled: Boolean,
	) {
		if (projectPath.isNullOrBlank()) {
			return
		}
		prefManager.putBoolean(getProjectWatermarkKey(projectPath), enabled)
	}

	fun isWatermarkEnabled(projectPath: String?): Boolean = shouldAddGlobalCommitWatermark && isProjectWatermarkEnabled(projectPath)

	fun getCanonicalProjectPath(projectPath: String): String {
		val file = File(projectPath.trim())
		return runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
	}

	fun getProjectWatermarkKey(projectPath: String): String = "$PROJECT_WATERMARK_PREFIX${getCanonicalProjectPath(projectPath)}"
}
