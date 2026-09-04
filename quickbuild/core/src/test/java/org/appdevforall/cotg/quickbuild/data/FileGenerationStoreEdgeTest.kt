package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The rename-fallback path of [FileGenerationStore.save] (delete-then-retry, for
 * filesystems where rename-over-existing fails) and the load guard for a path that
 * exists but is not a file.
 */
class FileGenerationStoreEdgeTest {
	@TempDir lateinit var tmp: File

	@Test
	fun `a generation path that is a directory loads as null`() =
		runTest {
			val dir = File(tmp, "generation").apply { mkdirs() }

			assertThat(FileGenerationStore(dir).load()).isNull()
		}

	/**
	 * Only a path that IS a file and still fails to open exercises the IOException guard,
	 * which keeps an unreadable state file from taking the session down: a lost counter costs
	 * one full rebuild, a throw here costs the feature.
	 *
	 * chmod 000 is not usable as the fixture - root (what container CI runs as) bypasses the
	 * read bit, so the test would skip exactly where the guard matters.
	 */
	@Test
	fun `a generation file that cannot be read starts fresh instead of throwing`() =
		runTest {
			val unopenable =
				object : File(tmp, "generation") {
					override fun isFile(): Boolean = true
				}

			assertThat(FileGenerationStore(unopenable).load()).isNull()
		}

	@Test
	fun `save falls back to delete-then-rename when the direct rename is refused`() =
		runTest {
			// An empty directory at the target defeats the direct rename (a file cannot
			// rename over a directory) but can be deleted - the retry must then land.
			val target = File(tmp, "generation").apply { mkdirs() }
			val store = FileGenerationStore(target)

			store.save(42)

			assertThat(target.isFile).isTrue()
			assertThat(store.load()).isEqualTo(42)
		}

	/**
	 * The arm that keeps the counter when neither rename can land: the old value is already
	 * deleted by then, so a throw would restart the sequence and let a later session reuse a
	 * generation the installed proxy app has already seen.
	 *
	 * The fixture defeats the retry rather than the write. An empty directory at the target
	 * refuses the direct rename; the override then removes the staged temp along with that
	 * directory, so the retry has nothing left to move and the direct write is the only way
	 * the value can survive.
	 */
	@Test
	fun `save writes the counter directly when the retry rename cannot run`() =
		runTest {
			val path = File(tmp, "generation").apply { mkdirs() }
			val target =
				object : File(path.absolutePath) {
					override fun delete(): Boolean {
						File(parentFile, "$name.tmp").delete()
						return super.delete()
					}
				}
			val store = FileGenerationStore(target)

			store.save(42)

			assertThat(FileGenerationStore(path).load()).isEqualTo(42)
		}

	@Test
	fun `save throws when the target cannot be replaced at all`() =
		runTest {
			// A NON-empty directory defeats both the rename and the delete; the store must
			// say so rather than silently keep the old state.
			val target = File(tmp, "generation").apply { mkdirs() }
			File(target, "occupant.txt").writeText("in the way")
			val store = FileGenerationStore(target)

			assertThat(runCatching { store.save(42) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
		}

	/**
	 * The callers sit on the session thread, which must not block, so every disk touch has to
	 * run on the injected dispatcher. Pinned through the file object: [File.isFile] is the
	 * first call load makes and [File.getParentFile] the first save makes, so the thread each
	 * lands on is the thread the I/O ran on.
	 */
	@Test
	fun `load and save run on the injected dispatcher, not the caller's thread`() =
		runTest {
			val ioThread = "qb-store-io-probe"
			val executor = Executors.newSingleThreadExecutor { Thread(it, ioThread) }
			val seen = CopyOnWriteArrayList<String>()
			val probed =
				object : File(tmp, "generation") {
					override fun isFile(): Boolean = super.isFile().also { seen += Thread.currentThread().name }

					override fun getParentFile(): File? = super.getParentFile().also { seen += Thread.currentThread().name }
				}
			try {
				val store = FileGenerationStore(probed, executor.asCoroutineDispatcher())

				store.save(9)
				assertThat(store.load()).isEqualTo(9)
			} finally {
				executor.shutdown()
			}

			assertThat(seen).isNotEmpty()
			assertThat(seen.toSet()).containsExactly(ioThread)
		}
}
