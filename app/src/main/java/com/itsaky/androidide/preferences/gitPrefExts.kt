package com.itsaky.androidide.preferences

import androidx.preference.Preference
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.R
import kotlinx.parcelize.Parcelize

@Parcelize
class GitPreferencesScreen(
    override val key: String = "idepref_git",
    override val title: Int = R.string.git_title,
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
    override val summary: Int? = null,
    override val icon: Int? = R.drawable.ic_account
) : EditTextPreference() {

    override fun onCreateView(context: android.content.Context): Preference {
        val pref = super.onCreateView(context)
        val currentName = GitPreferences.userName
        if (!currentName.isNullOrBlank()) {
            pref.summary = currentName
        } else {
            pref.summary = context.getString(R.string.idepref_git_user_name_summary)
        }
        return pref
    }

    override fun onConfigureTextInput(input: TextInputLayout) {
        input.editText?.setText(GitPreferences.userName)
        input.hint = input.context.getString(R.string.idepref_git_user_name_title)
    }

    override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
        val name = newValue as? String
        GitPreferences.userName = name
        if (!name.isNullOrBlank()) {
            preference.summary = name
        } else {
            preference.summary = preference.context.getString(R.string.idepref_git_user_name_summary)
        }
        return true
    }
}

@Parcelize
class GitUserEmail(
    override val key: String = GitPreferences.GIT_USER_EMAIL,
    override val title: Int = R.string.idepref_git_user_email_title,
    override val summary: Int? = null,
    override val icon: Int? = R.drawable.ic_email
) : EditTextPreference() {

    override fun onCreateView(context: android.content.Context): Preference {
        val pref = super.onCreateView(context)
        val currentEmail = GitPreferences.userEmail
        if (!currentEmail.isNullOrBlank()) {
            pref.summary = currentEmail
        } else {
            pref.summary = context.getString(R.string.idepref_git_user_email_summary)
        }
        return pref
    }

    override fun onConfigureTextInput(input: TextInputLayout) {
        input.editText?.setText(GitPreferences.userEmail)
        input.hint = input.context.getString(R.string.idepref_git_user_email_title)
    }

    override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
        val email = newValue as? String
        GitPreferences.userEmail = email
        if (!email.isNullOrBlank()) {
            preference.summary = email
        } else {
            preference.summary = preference.context.getString(R.string.idepref_git_user_email_summary)
        }
        return true
    }
}
