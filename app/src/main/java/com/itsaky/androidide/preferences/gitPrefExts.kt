package com.itsaky.androidide.preferences

import androidx.preference.Preference
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_GIT
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_GIT_USEREMAIL
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_GIT_USERNAME
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import kotlinx.parcelize.Parcelize

@Parcelize
class GitPreferencesScreen(
	override val key: String = "idepref_git",
	override val title: Int = R.string.git_title,
	override val summary: Int? = R.string.idepref_git_summary,
	override val children: List<IPreference> = mutableListOf(),
	override val tooltipTag: String = PREFS_GIT,
) : IPreferenceScreen() {

	init {
		addPreference(GitAuthorConfig())
		addPreference(CommitWatermarkConfig())
	}
}

@Parcelize
class GitAuthorConfig(
	override val key: String = "idepref_git_author",
	override val title: Int = R.string.idepref_git_author_title,
	override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

	init {
		addPreference(GitUserName())
		addPreference(GitUserEmail())
	}
}

@Parcelize
class CommitWatermarkConfig(
    override val key: String = "idepref_git_commit_watermark",
    override val title: Int = R.string.idepref_git_commit_watermark_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

    init {
        addPreference(AddGitCommitWatermark())
    }
}

@Parcelize
class GitUserName(
	override val key: String = GitPreferences.GIT_USER_NAME,
	override val title: Int = R.string.idepref_git_user_name_title,
	override val summary: Int? = null,
	override val icon: Int? = R.drawable.ic_account,
	override val tooltipTag: String = PREFS_GIT_USERNAME,
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
	override val icon: Int? = R.drawable.ic_email,
	override val tooltipTag: String = PREFS_GIT_USEREMAIL,
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

@Parcelize
private class AddGitCommitWatermark(
	override val key: String = GitPreferences.ADD_GLOBAL_COMMIT_WATERMARK,
	override val title: Int = string.idepref_git_add_commit_watermark,
	override val summary: Int? = string.idepref_git_add_commit_watermark_summary,
	override val icon: Int? = drawable.ic_watermark,
	override val tooltipTag: String = TooltipTag.PREFS_GIT_WATERMARK,
) : SwitchPreference(
	setValue = GitPreferences::shouldAddGlobalCommitWatermark::set,
	getValue = GitPreferences::shouldAddGlobalCommitWatermark::get
)
