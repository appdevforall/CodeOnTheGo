package com.itsaky.androidide.git.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import org.slf4j.LoggerFactory

/**
 * Manages Git credentials securely using standard SharedPreferences + CryptoManager.
 */
object GitCredentialsManager {

    private const val PREF_FILE_NAME = "git_credentials_prefs"
    private const val KEY_USERNAME_IV = "git_username_iv"
    private const val KEY_USERNAME_DATA = "git_username_data"
    private const val KEY_TOKEN_IV = "git_token_iv"
    private const val KEY_TOKEN_DATA = "git_token_data"

    private val log = LoggerFactory.getLogger(GitCredentialsManager::class.java)

    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        if (cachedPrefs == null) {
            cachedPrefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        }
        return cachedPrefs!!
    }

    fun saveCredentials(context: Context, username: String, token: String) {
        try {
            val (usernameIv, usernameData) = CryptoManager.encrypt(username)
            val (tokenIv, tokenData) = CryptoManager.encrypt(token)

            getPrefs(context).edit {
                putString(KEY_USERNAME_IV, Base64.encodeToString(usernameIv, Base64.NO_WRAP))
                putString(KEY_USERNAME_DATA, Base64.encodeToString(usernameData, Base64.NO_WRAP))
                putString(KEY_TOKEN_IV, Base64.encodeToString(tokenIv, Base64.NO_WRAP))
                putString(KEY_TOKEN_DATA, Base64.encodeToString(tokenData, Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            log.error("Failed to save credentials", e)
        }
    }

    fun getUsername(context: Context): String? = decrypt(context, KEY_USERNAME_IV, KEY_USERNAME_DATA)
    fun getToken(context: Context): String? = decrypt(context, KEY_TOKEN_IV, KEY_TOKEN_DATA)

    fun hasCredentials(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.contains(KEY_USERNAME_DATA) && prefs.contains(KEY_TOKEN_DATA)
    }

    private fun decrypt(context: Context, ivKey: String, dataKey: String): String? {
        val prefs = getPrefs(context)
        val ivBase64 = prefs.getString(ivKey, null) ?: return null
        val dataBase64 = prefs.getString(dataKey, null) ?: return null

        return try {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val data = Base64.decode(dataBase64, Base64.NO_WRAP)
            CryptoManager.decrypt(iv, data)
        } catch (e: Exception) {
            log.error("Failed to decrypt field: $dataKey", e)
            null
        }
    }
}
