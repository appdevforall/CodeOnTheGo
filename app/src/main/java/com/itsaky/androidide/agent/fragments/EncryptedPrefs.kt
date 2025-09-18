package com.itsaky.androidide.agent.fragments

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object EncryptedPrefs {

    private const val PREF_FILE_NAME = "secure_api_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"

    @Suppress("DEPRECATION")
    private fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREF_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveGeminiApiKey(context: Context, apiKey: String) {
        val prefs = getEncryptedSharedPreferences(context)
        prefs.edit {
            putString(KEY_GEMINI_API, apiKey)
        }
    }

    fun getGeminiApiKey(context: Context): String? {
        val prefs = getEncryptedSharedPreferences(context)
        return prefs.getString(KEY_GEMINI_API, null)
    }
}