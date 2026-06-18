/*
 *  This file is part of CodeOnTheGo.
 *
 *  CodeOnTheGo is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CodeOnTheGo is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with CodeOnTheGo.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.speech

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Manages voice-to-code preferences.
 */
object VoicePreferences {

    private const val PREF_VOICE_ENABLED = "voice_code_enabled"
    private const val PREF_VOICE_LANGUAGE = "voice_language"
    private const val PREF_SHOW_TYPING_ANIMATION = "voice_show_typing_animation"
    private const val PREF_PREVIEW_BEFORE_INSERT = "voice_preview_before_insert"
    private const val PREF_STT_MODE = "voice_stt_mode"

    // STT Mode constants
    const val STT_MODE_CLOUD = "cloud"
    const val STT_MODE_MOONSHINE = "moonshine"

    // Default values
    private const val DEFAULT_LANGUAGE = "en-US"
    private const val DEFAULT_ENABLED = true
    private const val DEFAULT_TYPING_ANIMATION = true
    private const val DEFAULT_PREVIEW = true
    private const val DEFAULT_STT_MODE = STT_MODE_CLOUD

    /**
     * Get SharedPreferences instance.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    /**
     * Check if voice code feature is enabled.
     */
    fun isVoiceCodeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_VOICE_ENABLED, DEFAULT_ENABLED)
    }

    /**
     * Set voice code enabled state.
     */
    fun setVoiceCodeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_VOICE_ENABLED, enabled).apply()
    }

    /**
     * Get selected voice language (e.g., "en-US", "es-ES").
     */
    fun getVoiceLanguage(context: Context): String {
        return getPrefs(context).getString(PREF_VOICE_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    /**
     * Set voice language.
     */
    fun setVoiceLanguage(context: Context, languageCode: String) {
        getPrefs(context).edit().putString(PREF_VOICE_LANGUAGE, languageCode).apply()
    }

    /**
     * Get language display name from code.
     */
    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        return when (languageCode) {
            "en-US" -> "🇺🇸 English"
            "es-ES" -> "🇪🇸 Español (España)"
            "es-MX" -> "🇲🇽 Español (México)"
            "es-AR" -> "🇦🇷 Español (Argentina)"
            else -> languageCode
        }
    }

    /**
     * Check if typing animation is enabled.
     */
    fun isTypingAnimationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_SHOW_TYPING_ANIMATION, DEFAULT_TYPING_ANIMATION)
    }

    /**
     * Set typing animation enabled state.
     */
    fun setTypingAnimationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_SHOW_TYPING_ANIMATION, enabled).apply()
    }

    /**
     * Check if preview before insert is enabled.
     */
    fun isPreviewBeforeInsertEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_PREVIEW_BEFORE_INSERT, DEFAULT_PREVIEW)
    }

    /**
     * Set preview before insert enabled state.
     */
    fun setPreviewBeforeInsertEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_PREVIEW_BEFORE_INSERT, enabled).apply()
    }

    /**
     * Get available language codes.
     */
    fun getAvailableLanguages(): List<String> {
        return listOf("en-US", "es-ES", "es-MX", "es-AR")
    }

    /**
     * Get available language display names.
     */
    fun getAvailableLanguageDisplayNames(context: Context): List<String> {
        return getAvailableLanguages().map { getLanguageDisplayName(context, it) }
    }

    /**
     * Get selected STT mode ("cloud" or "moonshine").
     */
    fun getSttMode(context: Context): String {
        return getPrefs(context).getString(PREF_STT_MODE, DEFAULT_STT_MODE) ?: DEFAULT_STT_MODE
    }

    /**
     * Set STT mode.
     */
    fun setSttMode(context: Context, mode: String) {
        require(mode == STT_MODE_CLOUD || mode == STT_MODE_MOONSHINE) {
            "Invalid STT mode: $mode"
        }
        getPrefs(context).edit().putString(PREF_STT_MODE, mode).apply()
    }

    /**
     * Check if using cloud-based STT (Android SpeechRecognizer).
     */
    fun isUsingCloudStt(context: Context): Boolean {
        return getSttMode(context) == STT_MODE_CLOUD
    }

    /**
     * Check if using offline Moonshine STT.
     */
    fun isUsingMoonshineStt(context: Context): Boolean {
        return getSttMode(context) == STT_MODE_MOONSHINE
    }

    /**
     * Get display name for STT mode.
     */
    fun getSttModeDisplayName(mode: String): String {
        return when (mode) {
            STT_MODE_CLOUD -> "Cloud (Android SpeechRecognizer)"
            STT_MODE_MOONSHINE -> "Offline (Moonshine)"
            else -> mode
        }
    }

    /**
     * Check if offline STT model is configured.
     * Returns true if a Speech-to-Text model path is saved in preferences.
     */
    fun isOfflineSttModelConfigured(context: Context): Boolean {
        val prefs = getPrefs(context)
        val sttModelPath = prefs.getString("local_model_path_speech_to_text", null)
        return !sttModelPath.isNullOrBlank()
    }

    /**
     * Get the path to the configured offline STT model.
     */
    fun getOfflineSttModelPath(context: Context): String? {
        val prefs = getPrefs(context)
        return prefs.getString("local_model_path_speech_to_text", null)
    }
}
