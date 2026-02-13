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

package com.itsaky.androidide.build.config

import org.gradle.api.Project
import java.io.File
import kotlin.getOrDefault

/**
 * Information about the CI build.
 *
 * @author Akash Yadav
 */
object CI {
	private var commitHash: String? = null
	private var branchName: String? = null

	fun commitHash(project: Project): String {
		if (commitHash == null) {
			val sha = System.getenv("GITHUB_SHA") ?: "HEAD"
			commitHash =
				project.cmdOutput(
					workDir = project.rootProject.projectDir,
					failOnError = false,
					"git",
					"rev-parse",
					"--short",
					sha,
				)
		}

		return commitHash ?: "unknown"
	}

	fun branchName(project: Project): String {
		if (branchName == null) {
			branchName = System.getenv("GITHUB_REF_NAME")
				?: project.cmdOutput(
					workDir = project.rootProject.projectDir,
					failOnError = false,
					"git",
					"rev-parse",
					"--abbrev-ref",
					"HEAD",
				)
		}

		return branchName ?: "unknown"
	}

	/** Whether the current build is a CI build. */
	val isCiBuild by lazy { "true" == System.getenv("CI") }

	/** Whether the current build is for tests. This is set ONLY in CI builds. */
	val isTestEnv by lazy { "true" == System.getenv("ANDROIDIDE_TEST") }

	private fun Project.cmdOutput(
		workDir: File,
		failOnError: Boolean = false,
		vararg args: String,
	): String? =
		runCatching {
			val process =
				ProcessBuilder(*args)
					.directory(workDir)
					.redirectErrorStream(true)
					.start()

			val exitCode = process.waitFor()
			if (exitCode != 0) {
				if (failOnError) {
					throw RuntimeException("Command '$args' failed with exit code $exitCode")
				}

				return null
			}

			process
				.inputStream
				.bufferedReader()
				.readText()
				.trim()
		}.onFailure { err ->
			logger.warn("Unable to run command: ${args.joinToString(" ")}", err)
		}.getOrDefault(null)
}
