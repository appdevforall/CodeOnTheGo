package com.itsaky.androidide.preferences

import androidx.preference.Preference
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.R
import kotlinx.parcelize.Parcelize

@Parcelize
class GitPreferencesScreen(
    override val key: String = "idepref_git",
    override val title: Int = R.string.idepref_git_title,
    override val summary: Int? = R.string.idepref_git_summary,
    override val children: List<IPreference> = mutableListOf()
) : IPreferenceScreen() {

    init {
        addPreference(GitAuthorConfig())
    }
}

@Parcelize
class GitAuthorConfig(
    override val key: String = "idepref_git_author",
    override val title: Int = R.string.idepref_git_author_title,
    override val summary: Int? = R.string.idepref_git_author_summary,
    override val children: List<IPreference> = mutableListOf()
) : IPreferenceGroup() {

    init {
        addPreference(GitUserName())
        addPreference(GitUserEmail())
    }
}

@Parcelize
class GitUserName(
    override val key: String = GitPreferences.GIT_USER_NAME,
    override val title: Int = R.string.idepref_git_user_name_title,
    override val summary: Int? = R.string.idepref_git_user_name_summary,
    override val icon: Int? = R.drawable.ic_account
) : EditTextPreference() {

    override fun onConfigureTextInput(input: TextInputLayout) {
        input.editText?.setText(GitPreferences.userName)
        input.hint = input.context.getString(R.string.idepref_git_user_name_title)
    }

    override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
        GitPreferences.userName = newValue as? String
        return true
    }
}

@Parcelize
class GitUserEmail(
    override val key: String = GitPreferences.GIT_USER_EMAIL,
    override val title: Int = R.string.idepref_git_user_email_title,
    override val summary: Int? = R.string.idepref_git_user_email_summary,
    override val icon: Int? = R.drawable.ic_email
) : EditTextPreference() {

    override fun onConfigureTextInput(input: TextInputLayout) {
        input.editText?.setText(GitPreferences.userEmail)
        input.hint = input.context.getString(R.string.idepref_git_user_email_title)
    }

    override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
        GitPreferences.userEmail = newValue as? String
        return true
    }
}
