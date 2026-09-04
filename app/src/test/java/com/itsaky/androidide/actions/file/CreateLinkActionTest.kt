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

package com.itsaky.androidide.actions.file

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The non-UI half of [CreateLinkAction]: deciding what path a link may name. A link gets handed to
 * another person, so "outside the project" has to be a hard no rather than a best effort.
 */
class CreateLinkActionTest {
	private val project = File("/storage/emulated/0/CodeOnTheGoProjects/MyApp")

	@Test
	fun `a file inside the project relativises to a slash-separated path`() {
		assertThat(projectRelativePathOrNull(project, File(project, "app/src/main/Main.kt")))
			.isEqualTo("app/src/main/Main.kt")
	}

	@Test
	fun `a file at the project root relativises to its bare name`() {
		assertThat(projectRelativePathOrNull(project, File(project, "settings.gradle.kts")))
			.isEqualTo("settings.gradle.kts")
	}

	@Test
	fun `a file outside the project is refused rather than escaping with dot-dot`() {
		// relativeToOrNull walks UP with ".." instead of failing, so a missing containment check here
		// would emit a link naming a file the project does not contain.
		assertThat(projectRelativePathOrNull(project, File("/storage/emulated/0/Download/secrets.txt"))).isNull()

		val sibling = File("/storage/emulated/0/CodeOnTheGoProjects/OtherApp/app/Main.kt")
		assertThat(projectRelativePathOrNull(project, sibling)).isNull()

		// The project's own parent, which relativises to exactly "..".
		assertThat(projectRelativePathOrNull(project, project.parentFile)).isNull()
	}

	@Test
	fun `the project directory itself has no relative path to name`() {
		assertThat(projectRelativePathOrNull(project, project)).isNull()
	}

	@Test
	fun `a hidden file inside the project is allowed`() {
		assertThat(projectRelativePathOrNull(project, File(project, ".gitignore"))).isEqualTo(".gitignore")
	}

	@Test
	fun `an unrelated absolute path elsewhere on the filesystem is refused`() {
		// Not "a different volume": on Android both of these share the root "/", so relativeToOrNull
		// succeeds and returns a "../.."-prefixed path. It is the containment check that refuses it,
		// which is the branch worth covering anyway.
		assertThat(projectRelativePathOrNull(project, File("/data/local/tmp/Main.kt"))).isNull()
	}
}
