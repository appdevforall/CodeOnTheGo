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

package com.itsaky.androidide.plugins.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a freshly generated API dump differs from the committed one, so a public API
 * change has to be acknowledged by running the matching dump task.
 */
abstract class CheckApiDumpTask : DefaultTask() {
	/** The dump generated from the current sources. */
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val generatedApiFile: RegularFileProperty

	/** The dump committed to the repository. */
	@get:InputFile
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val committedApiFile: RegularFileProperty

	/** The task path that refreshes [committedApiFile], named in the failure message. */
	@get:Input
	abstract val dumpTaskPath: Property<String>

	@TaskAction
	fun check() {
		val generated = generatedApiFile.get().asFile.readLines()
		val committed = committedApiFile.get().asFile.readLines()
		if (generated == committed) {
			return
		}

		val removed = committed - generated.toSet()
		val added = generated - committed.toSet()
		throw GradleException(
			buildString {
				appendLine("API check failed for ${committedApiFile.get().asFile}.")
				removed.take(MAX_REPORTED_LINES).forEach { appendLine("- $it") }
				added.take(MAX_REPORTED_LINES).forEach { appendLine("+ $it") }
				append("If the change is intended, run ${dumpTaskPath.get()} and commit the result.")
			},
		)
	}

	private companion object {
		const val MAX_REPORTED_LINES = 20
	}
}
