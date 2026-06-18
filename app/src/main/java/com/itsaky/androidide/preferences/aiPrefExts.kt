/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.preferences

import android.widget.Toast
import androidx.preference.Preference
import com.itsaky.androidide.R
import com.itsaky.androidide.speech.VoicePreferences
import com.itsaky.androidide.ui.voice.VoiceLanguageDialog
import kotlinx.parcelize.Parcelize

@Parcelize
class AIPreferencesScreen(
    override val key: String = "idepref_ai",
    override val title: Int = R.string.title_ai_settings,
    override val summary: Int? = R.string.idepref_ai_summary,
    override val children: List<IPreference> = mutableListOf()
) : IPreferenceScreen() {

    init {
        addPreference(VoiceCodeGroup())
    }
}

@Parcelize
class VoiceCodeGroup(
    override val key: String = "idepref_ai_voice",
    override val title: Int = R.string.title_voice_code,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

    init {
        addPreference(VoiceCodeEnabled())
        addPreference(SttModePreference())
        addPreference(VoiceLanguagePreference())
        addPreference(PreviewBeforeInsertPreference())
        addPreference(TypingAnimationPreference())
    }
}

@Parcelize
class VoiceCodeEnabled(
    override val key: String = "voice_code_enabled",
    override val title: Int = R.string.voice_code_enabled_title,
    override val summary: Int? = R.string.voice_code_enabled_summary,
    override val icon: Int? = null
) : SwitchPreference() {

    override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
        val enabled = newValue as? Boolean ?: return false
        VoicePreferences.setVoiceCodeEnabled(preference.context, enabled)
        return true
    }

    override fun onCreatePreference(preference: Preference) {
        super.onCreatePreference(preference)
        (preference as? androidx.preference.SwitchPreference)?.isChecked =
            VoicePreferences.isVoiceCodeEnabled(preference.context)
    }
}

@Parcelize
class SttModePreference(
    override val key: String = "voice_stt_mode",
    override val title: Int = R.string.voice_stt_mode_title,
    override val summary: Int? = R.string.voice_stt_mode_summary,
    override val icon: Int? = null
) : SingleChoicePreference() {

    override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
        val context = preference.context
        val currentMode = VoicePreferences.getSttMode(context)

        return arrayOf(
            PreferenceChoices.Entry(
                label = "Cloud (Google)",
                isChecked = currentMode == VoicePreferences.STT_MODE_CLOUD,
                value = VoicePreferences.STT_MODE_CLOUD
            ),
            PreferenceChoices.Entry(
                label = "Offline (Moonshine)",
                isChecked = currentMode == VoicePreferences.STT_MODE_MOONSHINE,
                value = VoicePreferences.STT_MODE_MOONSHINE
            )
        )
    }

    override fun onChoiceConfirmed(preference: Preference, entry: PreferenceChoices.Entry): Boolean {
        val mode = entry.value as String
        VoicePreferences.setSttMode(preference.context, mode)

        val modeDisplay = VoicePreferences.getSttModeDisplayName(mode)
        Toast.makeText(
            preference.context,
            "STT mode: $modeDisplay",
            Toast.LENGTH_SHORT
        ).show()

        return true
    }
}

@Parcelize
class VoiceLanguagePreference(
    override val key: String = "voice_language",
    override val title: Int = R.string.voice_language_title,
    override val summary: Int? = R.string.voice_language_summary,
    override val icon: Int? = null
) : SimpleClickablePreference() {

    override fun onClick(preference: Preference): Boolean {
        VoiceLanguageDialog.show(preference.context) { newLanguage ->
            val displayName = VoicePreferences.getLanguageDisplayName(preference.context, newLanguage)
            Toast.makeText(
                preference.context,
                "Language: $displayName",
                Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    override fun onCreatePreference(preference: Preference) {
        super.onCreatePreference(preference)
        val currentLanguage = VoicePreferences.getVoiceLanguage(preference.context)
        preference.summary = VoicePreferences.getLanguageDisplayName(preference.context, currentLanguage)
    }
}

@Parcelize
class PreviewBeforeInsertPreference(
    override val key: String = "voice_preview_before_insert",
    override val title: Int = R.string.voice_preview_title,
    override val summary: Int? = R.string.voice_preview_summary
) : SwitchPreference() {

    override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
        val enabled = newValue as? Boolean ?: return false
        VoicePreferences.setPreviewBeforeInsertEnabled(preference.context, enabled)
        return true
    }

    override fun onCreatePreference(preference: Preference) {
        super.onCreatePreference(preference)
        (preference as? androidx.preference.SwitchPreference)?.isChecked =
            VoicePreferences.isPreviewBeforeInsertEnabled(preference.context)
    }
}

@Parcelize
class TypingAnimationPreference(
    override val key: String = "voice_show_typing_animation",
    override val title: Int = R.string.voice_typing_animation_title,
    override val summary: Int? = R.string.voice_typing_animation_summary
) : SwitchPreference() {

    override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
        val enabled = newValue as? Boolean ?: return false
        VoicePreferences.setTypingAnimationEnabled(preference.context, enabled)
        return true
    }

    override fun onCreatePreference(preference: Preference) {
        super.onCreatePreference(preference)
        (preference as? androidx.preference.SwitchPreference)?.isChecked =
            VoicePreferences.isTypingAnimationEnabled(preference.context)
    }
}
