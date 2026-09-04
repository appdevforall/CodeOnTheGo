package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Key-sanitization and preparation edges of [QuickBuildScratch] beyond
 * [QuickBuildScratchTest]: filename-safe punctuation must survive the key, a nameless
 * root still yields a usable key, prepare is idempotent, and a blocked tree fails
 * with the user-facing message instead of throwing.
 */
class QuickBuildScratchEdgeTest {
	@TempDir lateinit var tmp: File

	private fun scratch() = QuickBuildScratch(File(tmp, "scratch-root"))

	@Test
	fun `dots underscores and dashes survive sanitization`() {
		val key = scratch().projectKey(File(tmp, "My.App_v2-final"))

		assertThat(key).startsWith("My.App_v2-final-")
	}

	@Test
	fun `a root without a name still gets a usable project key`() {
		// File("/") has an empty name; the key must not start with a bare dash.
		val key = scratch().projectKey(File("/"))

		assertThat(key).startsWith("project-")
	}

	@Test
	fun `an over-long basename is truncated but keeps the full hash`() {
		val longName = "a".repeat(120)
		val key = scratch().projectKey(File(tmp, longName))

		// 32 basename chars + dash + 16 hash chars.
		assertThat(key.length).isLessThan(longName.length)
		assertThat(key).matches("a+-[0-9a-f]{16}")
	}

	@Test
	fun `prepare is idempotent on an existing tree`() =
		runTest {
			val scratch = scratch()
			val project = File(tmp, "proj").apply { mkdirs() }
			val first = scratch.prepare(project) as QuickBuildScratch.Preparation.Ready
			File(first.dir, "work").mkdirs()

			val second = scratch.prepare(project)

			// The existing tree (and anything in it) is kept, not recreated.
			assertThat(second).isEqualTo(first)
			assertThat(File(first.dir, "work").isDirectory).isTrue()
		}

	@Test
	fun `a tree blocked by a stray file fails with the user-facing message`() =
		runTest {
			val scratch = scratch()
			val project = File(tmp, "proj").apply { mkdirs() }
			val tree = scratch.treeFor(project)
			tree.parentFile!!.mkdirs()
			tree.writeText("not a directory")

			val preparation = scratch.prepare(project)

			assertThat(preparation).isInstanceOf(QuickBuildScratch.Preparation.Failed::class.java)
			assertThat((preparation as QuickBuildScratch.Preparation.Failed).message)
				.isInstanceOf(QuickBuildMessage.ScratchDirUnavailable::class.java)
		}

	/**
	 * The callers sit on the session thread, which must not block, so every disk touch has to
	 * run on the injected dispatcher. Pinned through the root: [File.isDirectory] is the first
	 * call the space guard makes and [File.listFiles] the first sweep makes.
	 */
	@Test
	fun `disk work runs on the injected dispatcher, not the caller's thread`() =
		runTest {
			val ioThread = "qb-scratch-io-probe"
			val executor = Executors.newSingleThreadExecutor { Thread(it, ioThread) }
			val seen = CopyOnWriteArrayList<String>()
			val probedRoot =
				object : File(tmp, "scratch-root") {
					override fun isDirectory(): Boolean = super.isDirectory().also { seen += Thread.currentThread().name }

					override fun listFiles(): Array<File>? = super.listFiles().also { seen += Thread.currentThread().name }
				}
			try {
				val probed = QuickBuildScratch(probedRoot, ioDispatcher = executor.asCoroutineDispatcher())
				val project = File(tmp, "proj").apply { mkdirs() }

				assertThat(probed.freeSpaceShortfall()).isNull()
				assertThat(probed.prepare(project)).isInstanceOf(QuickBuildScratch.Preparation.Ready::class.java)
				probed.remove(project)
				probed.sweep()
			} finally {
				executor.shutdown()
			}

			assertThat(seen).isNotEmpty()
			assertThat(seen.toSet()).containsExactly(ioThread)
		}
}
