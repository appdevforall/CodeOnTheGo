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
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.appdevforall.codeonthego.utils

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.roomData.recentproject.RecentProject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentProjectLocationReconcilerTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `canonical path variants collapse and retain useful metadata`() {
		val projectDir = tempFolder.newFolder("project")
		File(projectDir, "nested").mkdir()
		val projects =
			listOf(
				RecentProject(
					id = 1,
					name = "Project",
					createdAt = "100",
					location = projectDir.absolutePath,
					lastModified = "400",
					templateName = "Basic App",
					language = "Kotlin",
				),
				RecentProject(
					id = 2,
					name = "Project",
					createdAt = "200",
					location = File(projectDir, "nested/..").path,
					lastModified = "300",
				),
			)

		val result = reconcileRecentProjectLocations(projects)

		assertThat(result).hasSize(1)
		assertThat(result.single().id).isEqualTo(2)
		assertThat(result.single().location).isEqualTo(projectDir.canonicalPath)
		assertThat(result.single().createdAt).isEqualTo("100")
		assertThat(result.single().lastModified).isEqualTo("400")
		assertThat(result.single().templateName).isEqualTo("Basic App")
		assertThat(result.single().language).isEqualTo("Kotlin")
	}

	@Test
	fun `different project locations with the same name stay separate`() {
		val first = tempFolder.newFolder("first", "SameName")
		val second = tempFolder.newFolder("second", "SameName")
		val projects =
			listOf(
				RecentProject(id = 1, name = "SameName", createdAt = "1", location = first.path),
				RecentProject(id = 2, name = "SameName", createdAt = "2", location = second.path),
			)

		val result = reconcileRecentProjectLocations(projects)

		assertThat(result).hasSize(2)
		assertThat(result.map { it.location }).containsExactly(first.canonicalPath, second.canonicalPath)
	}
}
