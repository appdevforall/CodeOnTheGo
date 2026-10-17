package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProjectLocationTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `create path and open path produce the same location for one folder reached through a symlink`() {
		val root = tempFolder.newFolder("real-projects")
		val project = File(root, "MyApp")
		assertThat(project.mkdirs()).isTrue()

		val alias = File(tempFolder.root, "alias")
		Files.createSymbolicLink(alias.toPath(), root.toPath())

		val createPath = File(alias, "MyApp")
		val openPath = File(root, "MyApp")

		assertThat(createPath.canonicalProjectLocation()).isEqualTo(openPath.canonicalProjectLocation())
	}
}
