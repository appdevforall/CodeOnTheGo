package com.itsaky.androidide.preferences.internal

/**
 * Preferences for Git configuration.
 */
object GitPreferences {
    const val GIT_USER_NAME = "git_user_name"
    const val GIT_USER_EMAIL = "git_user_email"

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
}
