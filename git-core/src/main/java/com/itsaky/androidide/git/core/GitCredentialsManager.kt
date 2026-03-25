package com.itsaky.androidide.git.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Manages Git credentials securely using EncryptedSharedPreferences.
 */
object GitCredentialsManager {

    private const val PREF_FILE_NAME = "git_secure_prefs"
    private const val KEY_USERNAME = "git_username"
    private const val KEY_TOKEN = "git_token"

    private fun getPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREF_FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveCredentials(context: Context, username: String, token: String) {
        getPrefs(context).edit {
            putString(KEY_USERNAME, username)
            putString(KEY_TOKEN, token)
        }
    }

    fun getUsername(context: Context): String? = getPrefs(context).getString(KEY_USERNAME, null)
    fun getToken(context: Context): String? = getPrefs(context).getString(KEY_TOKEN, null)

    fun hasCredentials(context: Context): Boolean {
        val prefs = getPrefs(context)
        return !prefs.getString(KEY_USERNAME, null).isNullOrBlank() &&
               !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit {
            remove(KEY_USERNAME)
            remove(KEY_TOKEN)
        }
    }
}
