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

import kotlinx.validation.api.dump
import kotlinx.validation.api.extractAnnotatedPackages
import kotlinx.validation.api.filterOutAnnotated
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayInputStream
import java.util.jar.JarFile

/**
 * Writes the public JVM API of a variant's classes in the binary-compatibility-validator dump
 * format.
 *
 * The validator's own tasks attach only to the `org.jetbrains.kotlin.android` plugin, which AGP 9
 * refuses to apply, so this task drives the validator's library API the same way its build task
 * does.
 */
@CacheableTask
abstract class GenerateApiDumpTask : DefaultTask() {
	/** Directories holding the variant's compiled classes. */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val classDirectories: ListProperty<Directory>

	/** Jars holding the variant's compiled classes. */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val classJars: ListProperty<RegularFile>

	/** Fully qualified names of classes excluded from the dump. */
	@get:Input
	abstract val ignoredClasses: SetProperty<String>

	/** Fully qualified names of annotations that mark a declaration as non-public. */
	@get:Input
	abstract val nonPublicMarkers: SetProperty<String>

	/** The dump file to write. */
	@get:OutputFile
	abstract val apiFile: RegularFileProperty

	@TaskAction
	fun generate() {
		val markers = nonPublicMarkers.get().map { it.replace('.', '/') }.toSet()
		val signatures = readClassFiles().map(::ByteArrayInputStream).asSequence().loadApiFromJvmClasses()
		val api =
			signatures
				.filterOutNonPublic(signatures.extractAnnotatedPackages(markers), ignoredClasses.get())
				.filterOutAnnotated(markers)

		apiFile
			.get()
			.asFile
			.bufferedWriter()
			.use { api.dump(it) }
	}

	private fun readClassFiles(): List<ByteArray> {
		val fromDirectories =
			classDirectories
				.get()
				.flatMap { dir -> dir.asFile.walk().filter { it.isFile && it.name.endsWith(".class") } }
				.map { it.readBytes() }

		val fromJars =
			classJars.get().flatMap { jar ->
				JarFile(jar.asFile).use { file ->
					file
						.entries()
						.asSequence()
						.filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
						.map { entry -> file.getInputStream(entry).use { it.readBytes() } }
						.toList()
				}
			}

		return fromDirectories + fromJars
	}
}
