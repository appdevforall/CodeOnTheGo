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

import androidx.core.content.ContextCompat
import androidx.preference.Preference
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.preferences.internal.GITHUB_USERNAME
import com.itsaky.androidide.preferences.internal.GITHUB_PAT
import com.itsaky.androidide.preferences.internal.SELECTED_THEME
import com.itsaky.androidide.preferences.internal.githubPAT
import com.itsaky.androidide.preferences.internal.githubUsername
import com.itsaky.androidide.preferences.internal.gradleInstallationDir
import com.itsaky.androidide.preferences.internal.selectedTheme
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.ui.themes.IDETheme
import com.itsaky.androidide.ui.themes.IThemeManager
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
    class GithubPreferences(
        override val key: String = "idepref_github",
        override val title: Int = R.string.title_github,
        override val summary: Int? = R.string.idepref_github_summary,
        override val children: List<IPreference> = mutableListOf()
    ) : IPreferenceScreen() {

        init {
            addPreference(GithubConfig())
        }
    }

    @Parcelize
    class GithubConfig(
        override val key: String = "idepref_github_interface",
        override val title: Int = R.string.title_github,
        override val children: List<IPreference> = mutableListOf(),
    ) : IPreferenceGroup() {

        init {
            addPreference(Username())
            addPreference(PersonalAccessToken())
        }
    }

    @Parcelize
    class Username(
        override val key: String = GITHUB_USERNAME,
        override val title: Int = com.itsaky.androidide.R.string.idepref_github_username,
        override val summary: Int? = com.itsaky.androidide.R.string.idepref_github_username_summary,
        override val icon: Int? = com.itsaky.androidide.R.drawable.ic_github
    ) : EditTextPreference() {

        @IgnoredOnParcel
        override val dialogCancellable = true

        override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
            githubUsername = newValue as String? ?: ""
            return true
        }

        override fun onConfigureTextInput(input: TextInputLayout) {
            input.setStartIconDrawable(R.drawable.ic_gradle)
            input.setHint(R.string.msg_github_username)
            input.isCounterEnabled = false
            input.editText!!.setText("")
        }
    }

    @Parcelize
    class PersonalAccessToken(
        override val key: String = GITHUB_PAT,
        override val title: Int = com.itsaky.androidide.R.string.idepref_github_pat,
        override val summary: Int? = com.itsaky.androidide.R.string.idepref_github_pat_summary,
        override val icon: Int? = com.itsaky.androidide.R.drawable.ic_github
    ) : EditTextPreference() {

        @IgnoredOnParcel
        override val dialogCancellable = true

        override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
            githubPAT = newValue as String? ?: ""
            return true
        }

        override fun onConfigureTextInput(input: TextInputLayout) {
            input.setStartIconDrawable(R.drawable.ic_gradle)
            input.setHint(R.string.msg_github_pat)
            input.isCounterEnabled = false
            input.editText!!.setText("")
        }
    }



