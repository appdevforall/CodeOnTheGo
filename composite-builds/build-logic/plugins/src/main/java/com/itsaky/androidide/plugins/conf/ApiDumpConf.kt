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

package com.itsaky.androidide.plugins.conf

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.itsaky.androidide.plugins.tasks.CheckApiDumpTask
import com.itsaky.androidide.plugins.tasks.GenerateApiDumpTask
import org.gradle.api.Project
import org.gradle.api.tasks.Copy

/**
 * Registers `apiBuild`, `apiCheck` and `apiDump` for an Android library, with the names and the
 * `api/<project>.api` dump file of the binary-compatibility-validator plugin, so existing CI
 * invocations keep working. `check` depends on `apiCheck`, as it did with the plugin.
 *
 * @param variantName The variant whose classes make up the public API, e.g. `release`.
 * @param configure Sets the dump filters, e.g. [GenerateApiDumpTask.ignoredClasses].
 */
fun Project.configureApiDump(
	variantName: String,
	configure: GenerateApiDumpTask.() -> Unit,
) {
	val committedDump = layout.projectDirectory.file("api/$name.api")

	val apiBuild =
		tasks.register("apiBuild", GenerateApiDumpTask::class.java) {
			apiFile.set(layout.buildDirectory.file("api/${project.name}.api"))
			configure()
		}

	extensions.getByType(AndroidComponentsExtension::class.java).apply {
		onVariants(selector().withName(variantName)) { variant ->
			variant.artifacts
				.forScope(ScopedArtifacts.Scope.PROJECT)
				.use(apiBuild)
				.toGet(
					ScopedArtifact.CLASSES,
					GenerateApiDumpTask::classJars,
					GenerateApiDumpTask::classDirectories,
				)
		}
	}

	val apiCheck =
		tasks.register("apiCheck", CheckApiDumpTask::class.java) {
			generatedApiFile.set(apiBuild.flatMap { it.apiFile })
			committedApiFile.set(committedDump)
			dumpTaskPath.set("${project.path}:apiDump")
		}

	tasks.register("apiDump", Copy::class.java) {
		from(apiBuild.flatMap { it.apiFile })
		into(committedDump.asFile.parentFile)
	}

	tasks.named("check") { dependsOn(apiCheck) }
}
