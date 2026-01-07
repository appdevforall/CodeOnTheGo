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

package com.itsaky.androidide.tooling.api.util

import com.itsaky.androidide.tooling.api.util.ToolingProps.DESCENDANT_FORCE_KILL_TIMEOUT_MS
import com.itsaky.androidide.utils.AndroidPluginVersion
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * System properties for configuring the tooling API server.
 *
 * @author Akash Yadav
 */
object ToolingProps {

	private fun propName(cat: String, name: String) = "ide.tooling.$cat.$name"

	/**
	 * Whether the current environment is a test environment.
	 *
	 * Internal API. For testing purposes only.
	 */
	val TESTING_IS_TEST_ENV = propName("testing", "isTestEnv")

	/**
	 * The latest AGP version known to the tooling API.
	 *
	 * Internal API. For testing purposes only.
	 */
	val TESTING_LATEST_AGP_VERSION = propName("testing", "latestAgpVersion")

	/**
	 * Whether the Gradle daemon should be killed forcibly. Use [DESCENDANT_FORCE_KILL_TIMEOUT_MS]
	 * to set a duration that the tooling server will wait before killing the daemon.
	 */
	val DAEMON_FORCE_KILL = propName("daemon", "forceKill")

	/**
	 * Timeout for killing the descendant processes. The tooling API waits for this timeout before
	 * killing forcibly killing the descendant processes if still alive. A timeout duration of
	 * `0` milliseconds kills the processes instantly.
	 */
	val DESCENDANT_FORCE_KILL_TIMEOUT_MS = propName("daemon", "killTimeoutMs")

	/**
	 * Whether the current environment is a test environment.
	 *
	 * Internal API. For testing purposes only.
	 */
	val isTestEnv: Boolean
		get() = System.getProperty(TESTING_IS_TEST_ENV).toBoolean()

	/**
	 * The latest AGP version known to the tooling API.
	 *
	 * Internal API. For testing purposes only.
	 */
	val latestTestedAgpVersion: AndroidPluginVersion
		get() {
			if (!isTestEnv) {
				return AndroidPluginVersion.LATEST_TESTED
			}

			return System.getProperty(TESTING_LATEST_AGP_VERSION)
				?.let { AndroidPluginVersion.parse(it) }
				?: AndroidPluginVersion.LATEST_TESTED
		}

	/**
	 * Whether the Gradle daemon should be killed forcibly.
	 */
	val killDescendantProcesses: Boolean
		get() = System.getProperty(DAEMON_FORCE_KILL).toBoolean()

	/**
	 * Timeout for killing the Gradle daemon.
	 */
	val killDescendantTimeout: Duration
		get() = System.getProperty(DESCENDANT_FORCE_KILL_TIMEOUT_MS, "0").toLong().milliseconds
}