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

package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectValidationsTest {
	@JvmField
	@Rule
	val tempFolder = TemporaryFolder()

	private fun makeValidProject(
		parent: File,
		name: String,
	): File {
		val project = File(parent, name).apply { mkdirs() }
		val appDir = File(project, "app").apply { mkdirs() }
		File(appDir, "build.gradle.kts").writeText("// stub")
		return project
	}

	@Test
	fun `resolves an existing project by name`() {
		val root = tempFolder.newFolder("projects")
		val project = makeValidProject(root, "MyApp")

		assertThat(findValidProjectByName(root, "MyApp")?.canonicalFile).isEqualTo(project.canonicalFile)
	}

	@Test
	fun `unknown project name yields null`() {
		val root = tempFolder.newFolder("projects")
		assertThat(findValidProjectByName(root, "DoesNotExist")).isNull()
	}

	@Test
	fun `dot-dot traversal outside projectsRoot is rejected`() {
		// Regression test: a bare File(projectsRoot, name) join let `name` escape projectsRoot
		// entirely (e.g. name = "../outside"). A real deep link supplies this as a decoded URL
		// segment, so a project sitting just outside the configured projects root must never be
		// resolvable via a crafted project name.
		val base = tempFolder.newFolder("base")
		val root = File(base, "projects").apply { mkdirs() }
		makeValidProject(base, "outside")

		assertThat(findValidProjectByName(root, "../outside")).isNull()
	}
}
