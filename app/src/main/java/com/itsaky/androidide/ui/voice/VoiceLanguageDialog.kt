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

package com.itsaky.androidide.ui.voice

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.itsaky.androidide.R
import com.itsaky.androidide.speech.VoicePreferences

/**
 * Dialog for selecting voice recognition language.
 */
object VoiceLanguageDialog {

    /**
     * Show language selection dialog.
     *
     * @param context Context
     * @param onLanguageSelected Callback when language is selected
     */
    fun show(context: Context, onLanguageSelected: ((String) -> Unit)? = null) {
        val languages = VoicePreferences.getAvailableLanguages()
        val displayNames = VoicePreferences.getAvailableLanguageDisplayNames(context)
        val currentLanguage = VoicePreferences.getVoiceLanguage(context)
        val selectedIndex = languages.indexOf(currentLanguage).coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(displayNames.toTypedArray(), selectedIndex) { dialog, which ->
                val newLanguage = languages[which]
                val displayName = displayNames[which]

                // Save preference
                VoicePreferences.setVoiceLanguage(context, newLanguage)

                // Show confirmation
                val message = context.getString(R.string.language_changed, displayName)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

                // Callback
                onLanguageSelected?.invoke(newLanguage)

                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.voice_language_info, null) // Info button
            .create()
            .apply {
                show()
                // Make info button not dismiss dialog
                getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    Toast.makeText(
                        context,
                        R.string.voice_language_info,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    /**
     * Get current language display name.
     */
    fun getCurrentLanguageDisplay(context: Context): String {
        val language = VoicePreferences.getVoiceLanguage(context)
        val displayName = VoicePreferences.getLanguageDisplayName(context, language)
        return context.getString(R.string.current_language, displayName)
    }
}
