package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileGenerationStoreTest {
	@TempDir lateinit var tempDir: File

	private fun store(name: String = "generation") = FileGenerationStore(File(tempDir, name))

	@Test
	fun `round trips a generation`() =
		runTest {
			val store = store()
			store.save(42)
			assertThat(store.load()).isEqualTo(42)
		}

	@Test
	fun `missing file loads as null`() =
		runTest {
			assertThat(store().load()).isNull()
		}

	@Test
	fun `corrupt file loads as null instead of throwing`() =
		runTest {
			val file = File(tempDir, "generation")
			file.writeText("not-a-number")
			assertThat(FileGenerationStore(file).load()).isNull()
		}

	@Test
	fun `only the first line is the counter`() =
		runTest {
			// A torn or appended-to file (a crash mid-write, a stray newline from a tool) must not
			// turn a persisted counter into a fresh session: that is a generation reused.
			val file = File(tempDir, "generation")
			file.writeText("42\njunk")
			assertThat(FileGenerationStore(file).load()).isEqualTo(42)
		}

	@Test
	fun `a save leaves no staging file behind`() =
		runTest {
			val store = store()
			store.save(3)
			assertThat(stagingFiles()).isEmpty()
		}

	@Test
	fun `a save never stages into a file another writer already staged`() =
		runTest {
			// Two stores on one path used to share a single "<name>.tmp"; the second writer's
			// rename would then publish whichever bytes the first had left there.
			File(tempDir, "generation.tmp").writeText("11")
			store().save(12)
			assertThat(store().load()).isEqualTo(12)
			assertThat(File(tempDir, "generation.tmp").readText()).isEqualTo("11")
		}

	@Test
	fun `empty file loads as null`() =
		runTest {
			val file = File(tempDir, "generation")
			file.writeText("")
			assertThat(FileGenerationStore(file).load()).isNull()
		}

	@Test
	fun `save creates missing parent directories`() =
		runTest {
			val file = File(tempDir, "nested/dirs/generation")
			val store = FileGenerationStore(file)
			store.save(7)
			assertThat(file.readText().trim()).isEqualTo("7")
		}

	@Test
	fun `save overwrites the previous value`() =
		runTest {
			val store = store()
			store.save(1)
			store.save(2)
			assertThat(store.load()).isEqualTo(2)
		}

	@Test
	fun `whitespace around the number is tolerated`() =
		runTest {
			val file = File(tempDir, "generation")
			file.writeText(" 13\n")
			assertThat(FileGenerationStore(file).load()).isEqualTo(13)
		}

	@Test
	fun `a save that cannot replace the target cleans up its temp file when it throws`() =
		runTest {
			// A non-empty directory squatting on the counter path defeats both renames AND the
			// direct-write fallback; the save must still throw - the value genuinely could not
			// be persisted - without leaving the .tmp orphan for the next load to trip on.
			val target = File(tempDir, "generation")
			target.mkdirs()
			File(target, "occupant").writeText("x")

			val thrown = runCatching { FileGenerationStore(target).save(5) }.exceptionOrNull()

			assertThat(thrown).isInstanceOf(java.io.IOException::class.java)
			assertThat(stagingFiles()).isEmpty()
		}

	/** @return every staged `.tmp` a save could have left beside the counter. */
	private fun stagingFiles(): List<File> = tempDir.listFiles { f -> f.name.startsWith("generation.") && f.name.endsWith(".tmp") }.orEmpty().toList()

	@Test
	fun `forProject uses the canonical androidide state path`() =
		runTest {
			val projectRoot = File(tempDir, "project")
			val store = FileGenerationStore.forProject(projectRoot)
			store.save(3)
			assertThat(File(projectRoot, ".androidide/quickbuild/generation").readText().trim())
				.isEqualTo("3")
		}
}
