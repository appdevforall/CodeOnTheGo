package com.itsaky.androidide.agent.fragments

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object EncryptedPrefs {

    private const val PREF_FILE_NAME = "secure_api_prefs"

    private const val KEY_GEMINI_API_TOKEN = "gemini_api_key_token"
    private const val KEY_GEMINI_API_TIMESTAMP = "gemini_api_key_timestamp"

    @Suppress("DEPRECATION")
    private fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREF_FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveGeminiApiKey(context: Context, apiKey: String) {
        val prefs = getEncryptedSharedPreferences(context)
        prefs.edit {
            putString(KEY_GEMINI_API_TOKEN, apiKey)
            putLong(KEY_GEMINI_API_TIMESTAMP, System.currentTimeMillis())
        }
    }

    fun getGeminiApiKey(context: Context): String? {
        val prefs = getEncryptedSharedPreferences(context)
        return prefs.getString(KEY_GEMINI_API_TOKEN, null)
    }

    fun getGeminiApiKeySaveTimestamp(context: Context): Long {
        val prefs = getEncryptedSharedPreferences(context)
        return prefs.getLong(KEY_GEMINI_API_TIMESTAMP, 0L)
    }

    fun clearGeminiApiKey(context: Context) {
        val prefs = getEncryptedSharedPreferences(context)
        prefs.edit {
            remove(KEY_GEMINI_API_TOKEN)
            remove(KEY_GEMINI_API_TIMESTAMP)
        }
    }
}