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

package com.itsaky.androidide.tooling.api;

/**
 * Configuration options for the Gradle plugin.
 *
 * @author Akash Yadav
 */
public final class GradlePluginConfig {

	/**
	 * Property used by the Gradle plugin to determine whether the Gradle build includes JDWP (debugging) support. This is usually set when for builds which are intended to be launched in debug mode.
	 */
	public static final String PROPERTY_JDWP_ENABLED = "cotg.jdwp.enabled";

	/**
	 * Property to enable or disable <code>LogSender</code> in the project. Value can be <code>true</code> or <code>false</code>.
	 */
	public static final String PROPERTY_LOG_SENDER_ENABLED = "androidide.logsender.isEnabled";

	/**
	 * The path to the LogSender AAR file.
	 */
	public static final String PROPERTY_LOG_SENDER_AAR = "androidide.logsender.aar";

	/**
	 * Property that is set in tests to indicate that the plugin is being applied in a test environment.
	 * <p>
	 * <b>This is an internal property and should not be manually set by users.</b>
	 */
	public static final String _PROPERTY_IS_TEST_ENV = "androidide.plugins.internal.isTestEnv";

	/**
	 * Property that is set in tests to provide path to the local maven repository. If this property is empty, `null` or not set at all, the default maven local repository is used.
	 *
	 * <b>This is an internal property and should not be manually set by users.</b>
	 */
	public static final String _PROPERTY_MAVEN_LOCAL_REPOSITORY = "androidide.plugins.internal.mavenLocalRepositories";

	private GradlePluginConfig() {
		throw new UnsupportedOperationException("This class cannot be instantiated.");
	}
}
