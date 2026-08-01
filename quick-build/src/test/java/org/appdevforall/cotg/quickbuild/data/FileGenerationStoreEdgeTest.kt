package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * The rename-fallback path of [FileGenerationStore.save] (delete-then-retry, for
 * filesystems where rename-over-existing fails) and the load guard for a path that
 * exists but is not a file.
 */
class FileGenerationStoreEdgeTest {
	@TempDir lateinit var tmp: File

	@Test
	fun `a generation path that is a directory loads as null`() {
		val dir = File(tmp, "generation").apply { mkdirs() }

		assertThat(FileGenerationStore(dir).load()).isNull()
	}

	@Test
	fun `save falls back to delete-then-rename when the direct rename is refused`() {
		// An empty directory at the target defeats the direct rename (a file cannot
		// rename over a directory) but can be deleted - the retry must then land.
		val target = File(tmp, "generation").apply { mkdirs() }
		val store = FileGenerationStore(target)

		store.save(42)

		assertThat(target.isFile).isTrue()
		assertThat(store.load()).isEqualTo(42)
	}

	@Test
	fun `save throws when the target cannot be replaced at all`() {
		// A NON-empty directory defeats both the rename and the delete; the store must
		// say so rather than silently keep the old state.
		val target = File(tmp, "generation").apply { mkdirs() }
		File(target, "occupant.txt").writeText("in the way")
		val store = FileGenerationStore(target)

		assertThrows(IOException::class.java) { store.save(42) }
	}
}
