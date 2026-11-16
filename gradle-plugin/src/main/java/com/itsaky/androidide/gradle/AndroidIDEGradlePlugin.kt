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
package com.itsaky.androidide.gradle

import com.itsaky.androidide.gradle.sqlite.SqliteRewritePlugin
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_JDWP_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_PROFILEABLE_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/**
 * Gradle Plugin for projects built in AndroidIDE.
 *
 * @author Akash Yadav
 */
class AndroidIDEGradlePlugin : Plugin<Project> {
	companion object {
		private val logger = Logging.getLogger(AndroidIDEGradlePlugin::class.java)

		/**
		 * QuickBuildPlugin's FQN, applied reflectively below so this file carries no
		 * compile-time reference to it: the minAgpCheck guard (see build.gradle.kts)
		 * recompiles every non-Quick-Build source against AGP_VERSION_MINIMUM, and only
		 * the Quick Build sources are allowed newer AGP APIs. Pinned to the real class by
		 * `QuickBuildPluginTest`.
		 */
		internal const val QUICK_BUILD_PLUGIN_CLASS = "com.itsaky.androidide.gradle.QuickBuildPlugin"

		/**
		 * Quick Build ships edited classes as a separate payload, compiled by its own daemon from the
		 * project's source and so never passed through the SQLite rewrite. Mixed with rewritten
		 * baseline classes it would hand android.database.sqlite objects to requery ones.
		 */
		internal const val QUICK_BUILD_SQLITE_DEBUGGER_MESSAGE =
			"Quick Build is not applied: cogo.sqliteDebugger=true moves the app to requery at build time, " +
				"and Quick Build's hot payload is compiled separately and would still use android.database.sqlite. " +
				"Building normally; use a Standard Run, or remove the property to use Quick Build."
	}

	override fun apply(target: Project) {
		if (target.isTestEnv) {
			logger.lifecycle("Applying ${javaClass.simpleName} to project '${target.path}'")
		}

		target.run {
			val isLogSenderEnabled = findProperty(PROPERTY_LOG_SENDER_ENABLED) == "true"
			if (isLogSenderEnabled) {
				pluginManager.apply(LogSenderPlugin::class.java)
			}

			// One opt-in for both (D13): the rewrite moves the app to requery in every variant, the
			// debugger needs requery and goes into the debuggable variants only.
			val isSqliteDebuggerEnabled = findProperty(PROPERTY_SQLITE_DEBUGGER_ENABLED) == "true"
			if (isSqliteDebuggerEnabled) {
				pluginManager.apply(SqliteRewritePlugin::class.java)
				pluginManager.apply(SqliteDebuggerPlugin::class.java)
			}

			val isJdwpEnabled = findProperty(PROPERTY_JDWP_ENABLED) == "true"
			if (isJdwpEnabled) {
				pluginManager.apply(JdwpPlugin::class.java)
			}

			val isProfileableEnabled = findProperty(PROPERTY_PROFILEABLE_ENABLED) == "true"
			if (isProfileableEnabled) {
				pluginManager.apply(ProfilerPlugin::class.java)
			}

			val isQuickBuildEnabled = findProperty(PROPERTY_QUICK_BUILD_ENABLED) == "true"
			if (isQuickBuildEnabled && isSqliteDebuggerEnabled) {
				logger.lifecycle(QUICK_BUILD_SQLITE_DEBUGGER_MESSAGE)
			} else if (isQuickBuildEnabled) {
				// By name, not ::class: Quick Build classes load (and touch newer AGP APIs)
				// only when the property enables them - see QUICK_BUILD_PLUGIN_CLASS.
				pluginManager.apply(Class.forName(QUICK_BUILD_PLUGIN_CLASS))
			}

			tasks.configureEach { task ->
				val message = adbTaskReplacementMessage(task) ?: return@configureEach
				task.setActions(emptyList())
				if (task.name.endsWith("AndroidTest")) {
					task.doLast { throw GradleException(ANDROID_TEST_INSTALL_UNSUPPORTED_MESSAGE) }
				} else {
					task.doLast { it.logger.lifecycle(message) }
				}
			}
		}
	}
}
