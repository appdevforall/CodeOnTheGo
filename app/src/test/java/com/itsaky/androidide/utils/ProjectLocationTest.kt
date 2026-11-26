package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
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

	@Test
	fun `an Exception from canonicalPath falls back to the absolute path`() {
		val file =
			object : File(tempFolder.root, "MyApp") {
				override fun getCanonicalPath(): String = throw IOException("cannot resolve")
			}

		assertThat(file.canonicalProjectLocation()).isEqualTo(file.absolutePath)
	}

	@Test
	fun `a LinkageError from canonicalPath falls back to the absolute path`() {
		val file =
			object : File(tempFolder.root, "MyApp") {
				override fun getCanonicalPath(): String = throw NoClassDefFoundError("missing")
			}

		assertThat(file.canonicalProjectLocation()).isEqualTo(file.absolutePath)
	}

	@Test
	fun `an OutOfMemoryError from canonicalPath propagates`() {
		val file =
			object : File(tempFolder.root, "MyApp") {
				override fun getCanonicalPath(): String = throw OutOfMemoryError("test")
			}

		val thrown = runCatching { file.canonicalProjectLocation() }.exceptionOrNull()

		assertThat(thrown).isInstanceOf(OutOfMemoryError::class.java)
	}
}
