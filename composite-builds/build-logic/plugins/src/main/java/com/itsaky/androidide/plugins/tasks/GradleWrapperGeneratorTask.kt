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

import org.adfa.constants.GRADLE_DISTRIBUTION_VERSION
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.wrapper.Wrapper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates the `gradle-wrapper.zip` file.
 *
 * @author Akash Yadav
 */
abstract class GradleWrapperGeneratorTask : DefaultTask() {

	/**
	 * The output directory.
	 */
	@get:OutputDirectory
	abstract val outputDirectory: DirectoryProperty

	@TaskAction
	fun generateGradleWrapperZip() {
		val outputDirectory = this.outputDirectory.get().file("data/common").asFile
		outputDirectory.mkdirs()

		val destFile = outputDirectory.resolve("gradle-wrapper.zip")

		if (destFile.exists()) {
			destFile.delete()
		}

		val stagingDir = File(outputDirectory, "staging")
		if (stagingDir.exists()) {
			stagingDir.deleteRecursively()
		}
		stagingDir.mkdirs()

		// Generate the files
		val wrapperDir = File(stagingDir, "gradle/wrapper")
		val wrapperProps = File(wrapperDir, "gradle-wrapper.properties")
		val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
		val unixScript = File(stagingDir, "gradlew")
		val dosScript = File(stagingDir, "gradlew.bat")
		val distributionUrl = "https\\://services.gradle.org/distributions/gradle-${GRADLE_DISTRIBUTION_VERSION}-bin.zip"
		IDEWrapperGenerator.generate(
			/* archiveBase = */ Wrapper.PathBase.GRADLE_USER_HOME,
			/* archivePath = */ "wrapper/dists",
			/* distributionBase = */ Wrapper.PathBase.GRADLE_USER_HOME,
			/* distributionPath = */ "wrapper/dists",
			/* distributionSha256Sum = */ null,
			/* wrapperPropertiesOutputFile = */ wrapperProps,
			/* wrapperJarOutputFile = */ wrapperJar,
			/* jarFileRelativePath = */ wrapperJar.relativeTo(stagingDir).path,
			/* unixScript = */ unixScript,
			/* batchScript = */ dosScript,
			/* distributionUrl = */ distributionUrl,
			/* validateDistributionUrl = */ true,
			/* networkTimeout = */ 10000,
		)

		// Archive all generated files
		ZipOutputStream(destFile.outputStream().buffered()).use { zipOut ->
			stagingDir.walk(direction = FileWalkDirection.TOP_DOWN)
				.filter { it.isFile }
				.forEach { file ->
					if (file.name != "gradlew.bat") {
						val entry = ZipEntry(file.relativeTo(stagingDir).path)
						zipOut.putNextEntry(entry)
						file.inputStream().buffered().use { fileInStream ->
							fileInStream.transferTo(zipOut)
						}
					}
				}

			zipOut.flush()
		}

		// finally, delete the staging directory
		stagingDir.deleteRecursively()
	}
}