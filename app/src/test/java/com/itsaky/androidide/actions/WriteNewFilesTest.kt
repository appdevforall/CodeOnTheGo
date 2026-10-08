package com.itsaky.androidide.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileAlreadyExistsException

class WriteNewFilesTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `writes every file with its content`() {
		val dir = tempFolder.newFolder("cpp")

		val created = writeNewFiles(dir, listOf("Renderer.h" to "header", "Renderer.cpp" to "source"))

		assertThat(created.map { it.name }).containsExactly("Renderer.h", "Renderer.cpp").inOrder()
		assertThat(File(dir, "Renderer.h").readText()).isEqualTo("header")
		assertThat(File(dir, "Renderer.cpp").readText()).isEqualTo("source")
	}

	@Test
	fun `an existing file is never overwritten`() {
		val dir = tempFolder.newFolder("cpp")
		File(dir, "util.c").writeText("user code")

		assertThrows(FileAlreadyExistsException::class.java) {
			writeNewFiles(dir, listOf("util.c" to ""))
		}

		assertThat(File(dir, "util.c").readText()).isEqualTo("user code")
	}

	@Test
	fun `a failure part way through removes the files this call already wrote`() {
		val dir = tempFolder.newFolder("cpp")
		File(dir, "Renderer.cpp").writeText("user code")

		assertThrows(FileAlreadyExistsException::class.java) {
			writeNewFiles(dir, listOf("Renderer.h" to "header", "Renderer.cpp" to "source"))
		}

		assertThat(File(dir, "Renderer.h").exists()).isFalse()
		assertThat(File(dir, "Renderer.cpp").readText()).isEqualTo("user code")
	}

	@Test
	fun `a path with folders creates them`() {
		val dir = tempFolder.newFolder("cpp")

		writeNewFiles(dir, listOf("include/defs.hpp" to ""))

		assertThat(File(dir, "include/defs.hpp").isFile).isTrue()
	}
}
