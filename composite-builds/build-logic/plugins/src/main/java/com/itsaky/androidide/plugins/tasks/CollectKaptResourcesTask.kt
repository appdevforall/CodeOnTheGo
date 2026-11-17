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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Copies the non-class files an annotation processor wrote through kapt, such as AutoService's
 * `META-INF/services` entries, into a directory AGP packages as Java resources.
 *
 * AGP 9's `com.android.legacy-kapt` routes kapt's classes output into the class pipeline only,
 * so resources generated there never reach the APK.
 */
@CacheableTask
abstract class CollectKaptResourcesTask : DefaultTask() {
	/** kapt's classes output directory. */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val kaptClassesDirectory: ConfigurableFileCollection

	/** The directory registered as a generated Java resources source. */
	@get:OutputDirectory
	abstract val resourcesDirectory: DirectoryProperty

	@get:Inject
	abstract val fileSystemOperations: FileSystemOperations

	@TaskAction
	fun collect() {
		fileSystemOperations.sync {
			from(kaptClassesDirectory)
			exclude("**/*.class")
			into(resourcesDirectory)
		}
	}
}
