package com.itsaky.androidide.lsp.kotlin

import KotlinPreferences
import com.itsaky.androidide.lsp.util.PrefBasedServerSettings
import com.itsaky.androidide.managers.PreferenceManager
import com.itsaky.androidide.utils.VMUtils

/**
 * Server settings for the Kotlin language server.
 *
 * @author Vladimir Arevshatyan
 */
class KotlinServerSettings : PrefBasedServerSettings() {

    companion object {
        const val KEY_KOTLIN_PREF_GOOGLE_CODE_STYLE = KotlinPreferences.GOOGLE_CODE_STYLE
        const val CODE_STYLE_AOSP = 0
        const val CODE_STYLE_GOOGLE = 1
        private var instance: KotlinServerSettings? = null

        @JvmStatic
        fun getInstance(): KotlinServerSettings {
            if (instance == null) {
                instance = KotlinServerSettings()
            }
            return instance!!
        }
    }

    override fun diagnosticsEnabled(): Boolean {
        return VMUtils.isJvm() || KotlinPreferences.isKotlinDiagnosticsEnabled
    }

    fun getStyle(): String {
        return if (getCodeStyle() == CODE_STYLE_AOSP) "AOSP" else "GOOGLE"
    }

    private fun getCodeStyle(): Int {
        val prefs: PreferenceManager? = getPrefs()
        return if (prefs?.getBoolean(KEY_KOTLIN_PREF_GOOGLE_CODE_STYLE, false) == true) {
            CODE_STYLE_GOOGLE
        } else {
            CODE_STYLE_AOSP
        }
    }
}