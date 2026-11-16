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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates the `gradle-wrapper.zip` file from the files a Gradle `Wrapper` task wrote to
 * [wrapperDirectory], leaving out `gradlew.bat`.
 *
 * @author Akash Yadav
 */
abstract class GradleWrapperGeneratorTask : DefaultTask() {
	/**
	 * The directory holding `gradlew` and `gradle/wrapper/`.
	 */
	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val wrapperDirectory: DirectoryProperty

	/**
	 * The output directory.
	 */
	@get:OutputDirectory
	abstract val outputDirectory: DirectoryProperty

	@TaskAction
	fun generateGradleWrapperZip() {
		val wrapperDirectory = this.wrapperDirectory.get().asFile
		val outputDirectory =
			this.outputDirectory
				.get()
				.file("data/common")
				.asFile
		outputDirectory.mkdirs()

		val destFile = outputDirectory.resolve("gradle-wrapper.zip")

		if (destFile.exists()) {
			destFile.delete()
		}

		ZipOutputStream(destFile.outputStream().buffered()).use { zipOut ->
			wrapperDirectory
				.walk(direction = FileWalkDirection.TOP_DOWN)
				.filter { it.isFile }
				.forEach { file ->
					if (file.name != "gradlew.bat") {
						val entry = ZipEntry(file.relativeTo(wrapperDirectory).path)
						zipOut.putNextEntry(entry)
						file.inputStream().buffered().use { fileInStream ->
							fileInStream.transferTo(zipOut)
						}
					}
				}

			zipOut.flush()
		}
	}
}
