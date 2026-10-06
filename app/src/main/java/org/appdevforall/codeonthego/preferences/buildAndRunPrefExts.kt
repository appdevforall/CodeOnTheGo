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

package org.appdevforall.codeonthego.preferences

import com.itsaky.androidide.R
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_AUTOLAUNCH
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_BUILDCACHE
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_DEBUG
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_INFO
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_OFFLINE
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_SCAN
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_STACKTRACE
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_WARNINGMODEALL
import org.appdevforall.codeonthego.idetooltips.TooltipTag.PREFS_BUILD_RUN
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.GRADLE_COMMANDS
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.LAUNCH_APP_AFTER_INSTALL
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.isBuildCacheEnabled
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.isDebugEnabled
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.isInfoEnabled
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.isOfflineEnabled
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.isScanEnabled
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.isStacktraceEnabled
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.isWarningModeAllEnabled
import org.appdevforall.codeonthego.preferences.internal.BuildPreferences.launchAppAfterInstall
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import kotlinx.parcelize.Parcelize

@Parcelize
class BuildAndRunPreferences(
	override val key: String = "idepref_build_n_run",
	override val title: Int = string.idepref_build_title,
	override val summary: Int? = string.idepref_buildnrun_summary,
	override val children: List<IPreference> = mutableListOf(),
	override val tooltipTag: String = PREFS_BUILD_RUN,
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
	}
}

@Parcelize
private class GradleCommands(
	override val key: String = GRADLE_COMMANDS,
	override val title: Int = string.idepref_build_customgradlecommands_title,
	override val summary: Int? = string.idepref_build_customgradlecommands_summary,
	override val icon: Int? = drawable.ic_bash_commands,
	override val tooltipTag: String = PREFS_BUILDRUN_FLAGS,
) : PropertyBasedMultiChoicePreference() {

	override fun getProperties(): List<PreferenceChoices.Entry> {
		return listOf(
			propertyEntry("--stacktrace", ::isStacktraceEnabled, PREFS_BUILDRUN_FLAGS_STACKTRACE),
			propertyEntry("--info", ::isInfoEnabled, PREFS_BUILDRUN_FLAGS_INFO),
			propertyEntry("--debug", ::isDebugEnabled, PREFS_BUILDRUN_FLAGS_DEBUG),
			propertyEntry("--scan", ::isScanEnabled, PREFS_BUILDRUN_FLAGS_SCAN),
			propertyEntry("--warning-mode all", ::isWarningModeAllEnabled, PREFS_BUILDRUN_FLAGS_WARNINGMODEALL),
			propertyEntry("--build-cache", ::isBuildCacheEnabled, PREFS_BUILDRUN_FLAGS_BUILDCACHE),
			propertyEntry("--offline", ::isOfflineEnabled, PREFS_BUILDRUN_FLAGS_OFFLINE),
		)
	}
}


@Parcelize
private class RunOptions(
	override val key: String = "ide.build.runOptions",
	override val title: Int = R.string.title_run_options,
	override val children: List<IPreference> = mutableListOf(),
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
	override val icon: Int? = drawable.ic_open_external,
	override val tooltipTag: String = PREFS_BUILDRUN_AUTOLAUNCH,
) :
	SwitchPreference(setValue = ::launchAppAfterInstall::set, getValue = ::launchAppAfterInstall::get)
