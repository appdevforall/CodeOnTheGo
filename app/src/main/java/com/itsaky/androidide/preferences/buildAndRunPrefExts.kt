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

import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_AUTOLAUNCH
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_BUILDCACHE
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_DEBUG
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_INFO
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_OFFLINE
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_SCAN
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_STACKTRACE
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_FLAGS_WARNINGMODEALL
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILDRUN_RELOCATE_OUTPUT
import com.itsaky.androidide.idetooltips.TooltipTag.PREFS_BUILD_RUN
import com.itsaky.androidide.preferences.internal.BuildPreferences.GRADLE_COMMANDS
import com.itsaky.androidide.preferences.internal.BuildPreferences.LAUNCH_APP_AFTER_INSTALL
import com.itsaky.androidide.preferences.internal.BuildPreferences.RELOCATE_BUILD_OUTPUT
import com.itsaky.androidide.preferences.internal.BuildPreferences.isBuildCacheEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isDebugEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isInfoEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isOfflineEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isScanEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isStacktraceEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.isWarningModeAllEnabled
import com.itsaky.androidide.preferences.internal.BuildPreferences.launchAppAfterInstall
import com.itsaky.androidide.preferences.internal.BuildPreferences.relocateBuildOutput
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.FeatureFlags
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
		// Early access only (REVIEW.md section 12): until the rest of the ADFA-4930 stack lands,
		// Quick Build and plugin builds still look for output in the project folder.
		if (FeatureFlags.isExperimentsEnabled) {
			addPreference(RelocateBuildOutput())
		}
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
private class RelocateBuildOutput(
	override val key: String = RELOCATE_BUILD_OUTPUT,
	override val title: Int = string.idepref_relocateBuildOutput_title,
	override val summary: Int? = string.idepref_relocateBuildOutput_summary,
	override val icon: Int? = drawable.ic_folder,
	override val tooltipTag: String = PREFS_BUILDRUN_RELOCATE_OUTPUT,
) : SwitchPreference(setValue = ::relocateBuildOutput::set, getValue = ::relocateBuildOutput::get)

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
