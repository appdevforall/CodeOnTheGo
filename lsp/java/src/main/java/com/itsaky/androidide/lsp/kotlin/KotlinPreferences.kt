import com.itsaky.androidide.preferences.internal.prefManager

/**
 * Kotlin Preferences for AndroidIDE.
 *
 * @author Vladimir Arevshatyan
 */
@Suppress("MemberVisibilityCanBePrivate")
object KotlinPreferences {

    const val GOOGLE_CODE_STYLE = "idepref_editor_kotlin_googleCodeStyle"
    const val KOTLIN_DIAGNOSTICS_ENABLED = "idepref_editor_kotlin_diagnosticsEnabled"

    var googleCodeStyle: Boolean
        get() = prefManager.getBoolean(GOOGLE_CODE_STYLE, false)
        set(value) {
            prefManager.putBoolean(GOOGLE_CODE_STYLE, value)
        }

    /** Whether diagnostics are enabled for Kotlin source files. */
    var isKotlinDiagnosticsEnabled: Boolean
        get() = prefManager.getBoolean(KOTLIN_DIAGNOSTICS_ENABLED, true)
        set(value) {
            prefManager.putBoolean(KOTLIN_DIAGNOSTICS_ENABLED, value)
        }
}
