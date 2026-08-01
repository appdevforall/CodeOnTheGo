package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileGenerationStoreTest {
	@TempDir lateinit var tempDir: File

	private fun store(name: String = "generation") = FileGenerationStore(File(tempDir, name))

	@Test
	fun `round trips a generation`() {
		val store = store()
		store.save(42)
		assertThat(store.load()).isEqualTo(42)
	}

	@Test
	fun `missing file loads as null`() {
		assertThat(store().load()).isNull()
	}

	@Test
	fun `corrupt file loads as null instead of throwing`() {
		val file = File(tempDir, "generation")
		file.writeText("not-a-number")
		assertThat(FileGenerationStore(file).load()).isNull()
	}

	@Test
	fun `empty file loads as null`() {
		val file = File(tempDir, "generation")
		file.writeText("")
		assertThat(FileGenerationStore(file).load()).isNull()
	}

	@Test
	fun `save creates missing parent directories`() {
		val file = File(tempDir, "nested/dirs/generation")
		val store = FileGenerationStore(file)
		store.save(7)
		assertThat(file.readText().trim()).isEqualTo("7")
	}

	@Test
	fun `save overwrites the previous value`() {
		val store = store()
		store.save(1)
		store.save(2)
		assertThat(store.load()).isEqualTo(2)
	}

	@Test
	fun `whitespace around the number is tolerated`() {
		val file = File(tempDir, "generation")
		file.writeText(" 13\n")
		assertThat(FileGenerationStore(file).load()).isEqualTo(13)
	}

	@Test
	fun `forProject uses the canonical androidide state path`() {
		val projectRoot = File(tempDir, "project")
		val store = FileGenerationStore.forProject(projectRoot)
		store.save(3)
		assertThat(File(projectRoot, ".androidide/quickbuild/generation").readText().trim())
			.isEqualTo("3")
	}
}
