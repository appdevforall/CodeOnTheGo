package com.itsaky.androidide.agent.fragments

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object EncryptedPrefs {

    private const val PREF_FILE_NAME = "secure_api_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"

    private fun getEncryptedSharedPreferences(context: Context): EncryptedSharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        return EncryptedSharedPreferences.create(
            PREF_FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    fun saveGeminiApiKey(context: Context, apiKey: String) {
        val prefs = getEncryptedSharedPreferences(context)
        with(prefs.edit()) {
            putString(KEY_GEMINI_API, apiKey)
            apply()
        }
    }

    fun getGeminiApiKey(context: Context): String? {
        val prefs = getEncryptedSharedPreferences(context)
        return prefs.getString(KEY_GEMINI_API, null)
    }
}