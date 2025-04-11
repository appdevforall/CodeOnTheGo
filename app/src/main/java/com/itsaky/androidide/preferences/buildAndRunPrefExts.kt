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

import android.content.Context
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.models.JdkDistribution
import com.itsaky.androidide.preferences.internal.BuildPreferences.CUSTOM_GRADLE_INSTALLATION
import com.itsaky.androidide.preferences.internal.BuildPreferences.GRADLE_CLEAR_CACHE
import com.itsaky.androidide.preferences.internal.BuildPreferences.GRADLE_COMMANDS
import com.itsaky.androidide.preferences.internal.BuildPreferences.LAUNCH_APP_AFTER_INSTALL
import com.itsaky.androidide.preferences.internal.BuildPreferences.gradleInstallationDir
import com.itsaky.androidide.preferences.internal.BuildPreferences.isBuildCacheEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isDebugEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isInfoEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isOfflineEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isScanEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isStacktraceEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isWarningModeAllEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.launchAppAfterInstall
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.utils.Environment.GRADLE_USER_HOME
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.parcelize.Parcelize
import java.io.File
import kotlin.reflect.KMutableProperty0

@Parcelize
class BuildAndRunPreferences(
    override val key: String = "idepref_build_n_run",
    override val title: Int = string.idepref_build_title,
    override val summary: Int? = string.idepref_buildnrun_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

    init {
        addPreference(GradleOptions())
        addPreference(RunOptions())
    }
}

@Parcelize
private class GradleOptions(
    override val key: String = "idepref_build_gradle",
    override val title: Int = string.gradle,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(GradleCommands())
    addPreference(GradleClearCache())
  }
}

@Parcelize
private class GradleCommands(
    override val key: String = GRADLE_COMMANDS,
    override val title: Int = string.idepref_build_customgradlecommands_title,
    override val summary: Int? = string.idepref_build_customgradlecommands_summary,
    override val icon: Int? = drawable.ic_bash_commands,
) : PropertyBasedMultiChoicePreference() {

    override fun getProperties(): Map<String, KMutableProperty0<Boolean>> {
        return linkedMapOf(
            "--stacktrace" to ::isStacktraceEnabled,
            "--info" to ::isInfoEnabled,
            "--debug" to ::isDebugEnabled,
            "--scan" to ::isScanEnabled,
            "--warning-mode all" to ::isWarningModeAllEnabled,
            "--build-cache" to ::isBuildCacheEnabled,
            "--offline" to ::isOfflineEnabled,
        )
    }
}

@Parcelize
private class GradleClearCache(
    override val key: String = GRADLE_CLEAR_CACHE,
    override val title: Int = string.idepref_build_clearCache_title,
    override val summary: Int? = string.idepref_build_clearCache_summary,
    override val icon: Int? = drawable.ic_delete,
    override val dialogMessage: Int? = string.msg_clear_cache
) : DialogPreference() {

    override fun onConfigureDialog(preference: Preference, dialog: MaterialAlertDialogBuilder) {
        super.onConfigureDialog(preference, dialog)
        dialog.setPositiveButton(string.yes) { dlg, _ ->
            dlg.dismiss()
            executeAsync(callable = this::deleteCaches) {
                if (it == true) {
                    flashSuccess(string.deleted)
                } else {
                    flashError(string.delete_failed)
                }
            }
        }
        dialog.setNegativeButton(string.no) { dlg, _ -> dlg.dismiss() }
    }

    private fun deleteCaches(): Boolean {
        val caches = File(GRADLE_USER_HOME, "caches")
        if (caches.exists()) {
            return caches.deleteRecursively()
        }
        return false
    }
}

@Parcelize
private class RunOptions(
    override val key: String = "ide.build.runOptions",
    override val title: Int = R.string.title_run_options,
    override val children: List<IPreference> = mutableListOf()
) : IPreferenceGroup() {

    init {
        addPreference(LaunchAppAfterInstall())
    }
}

@Parcelize
private class LaunchAppAfterInstall(
    override val key: String = LAUNCH_APP_AFTER_INSTALL,
    override val title: Int = R.string.idepref_launchAppAfterInstall_title,
    override val summary: Int? = R.string.idepref_launchAppAfterInstall_summary,
    override val icon: Int? = drawable.ic_open_external
) :
  SwitchPreference(setValue = ::launchAppAfterInstall::set, getValue = ::launchAppAfterInstall::get)
