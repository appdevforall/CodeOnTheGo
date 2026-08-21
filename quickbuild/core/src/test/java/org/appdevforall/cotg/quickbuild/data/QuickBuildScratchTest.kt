package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class QuickBuildScratchTest {
	@TempDir lateinit var root: File

	@TempDir lateinit var projects: File

	private val scratch by lazy { QuickBuildScratch(root) }

	@Test
	fun `same project maps to the same key across instances`() {
		val project = File(projects, "MyApp")
		val again = QuickBuildScratch(root)

		assertThat(scratch.projectKey(project)).isEqualTo(again.projectKey(project))
		assertThat(scratch.treeFor(project)).isEqualTo(again.treeFor(project))
	}

	@Test
	fun `key is stable under redundant path segments`() {
		val plain = File(projects, "MyApp")
		val dotted = File(projects, "sub/../MyApp")

		assertThat(scratch.projectKey(dotted)).isEqualTo(scratch.projectKey(plain))
	}

	@Test
	fun `distinct projects sharing a basename get distinct trees`() {
		val a = File(projects, "a/MyApp")
		val b = File(projects, "b/MyApp")

		assertThat(scratch.treeFor(a)).isNotEqualTo(scratch.treeFor(b))
		// Both stay directly under the root - the basename part never nests.
		assertThat(scratch.treeFor(a).parentFile).isEqualTo(root)
		assertThat(scratch.treeFor(b).parentFile).isEqualTo(root)
	}

	@Test
	fun `key sanitizes filename-hostile characters but keeps the hash`() {
		val weird = File(projects, "My App (v2)!")
		val key = scratch.projectKey(weird)

		assertThat(key).matches("[A-Za-z0-9._-]+")
		assertThat(key).contains("My_App")
	}

	@Test
	fun `work and out dirs are siblings inside the project tree`() {
		val project = File(projects, "MyApp")

		assertThat(scratch.workDirFor(project).parentFile).isEqualTo(scratch.treeFor(project))
		assertThat(scratch.outDirFor(project).parentFile).isEqualTo(scratch.treeFor(project))
		assertThat(scratch.workDirFor(project)).isNotEqualTo(scratch.outDirFor(project))
	}

	@Test
	fun `prepare creates the tree and reports ready`() {
		val project = File(projects, "MyApp")

		val prepared = scratch.prepare(project)

		assertThat(prepared).isInstanceOf(QuickBuildScratch.Preparation.Ready::class.java)
		assertThat((prepared as QuickBuildScratch.Preparation.Ready).dir.isDirectory).isTrue()
		assertThat(prepared.dir).isEqualTo(scratch.treeFor(project))
	}

	@Test
	fun `prepare fails with a user-facing message when the volume is below the floor`() {
		// A floor no real filesystem satisfies forces the shortfall branch.
		val guarded = QuickBuildScratch(root, minFreeBytes = Long.MAX_VALUE)

		val prepared = guarded.prepare(File(projects, "MyApp"))

		assertThat(prepared).isInstanceOf(QuickBuildScratch.Preparation.Failed::class.java)
		// Named, with the two numbers the host's copy interpolates - the wording itself
		// lives in the app module's resources.
		val message = (prepared as QuickBuildScratch.Preparation.Failed).message
		assertThat(message).isInstanceOf(QuickBuildMessage.NotEnoughStorage::class.java)
		assertThat((message as QuickBuildMessage.NotEnoughStorage).requiredMb).isGreaterThan(0L)
		// The failure never half-creates the tree.
		assertThat(guarded.treeFor(File(projects, "MyApp")).exists()).isFalse()
	}

	@Test
	fun `freeSpaceShortfall is null when the volume has room`() {
		assertThat(scratch.freeSpaceShortfall()).isNull()
	}

	@Test
	fun `remove deletes the tree and tolerates a missing one`() {
		val project = File(projects, "MyApp")
		val tree = (scratch.prepare(project) as QuickBuildScratch.Preparation.Ready).dir
		File(tree, "out/classes/Foo.class").apply {
			parentFile!!.mkdirs()
			writeText("bytecode")
		}

		scratch.remove(project)
		assertThat(tree.exists()).isFalse()

		// Second remove: nothing there, nothing thrown.
		scratch.remove(project)
	}

	@Test
	fun `sweep removes every tree, including a populated one`() {
		val first = File(projects, "FirstApp")
		val second = File(projects, "SecondApp")
		val firstTree = (scratch.prepare(first) as QuickBuildScratch.Preparation.Ready).dir
		val secondTree = (scratch.prepare(second) as QuickBuildScratch.Preparation.Ready).dir
		File(secondTree, "out/stale.dex").apply {
			parentFile!!.mkdirs()
			writeText("stale")
		}

		scratch.sweep()

		assertThat(firstTree.exists()).isFalse()
		assertThat(secondTree.exists()).isFalse()
	}

	@Test
	fun `sweep reclaims the tree of a deleted project`() {
		val project = File(projects, "Doomed").apply { mkdirs() }
		val tree = (scratch.prepare(project) as QuickBuildScratch.Preparation.Ready).dir

		// The project folder is gone; only the key (derived from the path string)
		// remains - the sweep must still find and delete the orphan tree.
		project.deleteRecursively()
		scratch.sweep()

		assertThat(tree.exists()).isFalse()
	}

	@Test
	fun `sweep leaves stray files and tolerates a missing root`() {
		val stray = File(root, "not-a-tree.txt").apply { writeText("keep me") }
		scratch.sweep()
		assertThat(stray.exists()).isTrue()

		root.deleteRecursively()
		// Missing root: listFiles() is null; nothing thrown.
		scratch.sweep()
	}

	/**
	 * Pins [dir] shut by clearing its write bit, so nothing inside it can be unlinked and
	 * the non-empty directory itself cannot go either. Skips the calling test when the runner
	 * writes into it anyway - root, or a filesystem that ignores the bit - since there is then
	 * no delete failure to observe.
	 *
	 * @param dir the directory to make undeletable; it must already exist and be non-empty.
	 */
	private fun pinShut(dir: File) {
		dir.setWritable(false)
		assumeTrue(!File(dir, "write-probe").mkdirs(), "the runner can still write into a read-only dir")
	}

	@Test
	fun `remove reports an undeletable tree instead of throwing`() {
		val project = File(projects, "Stuck")
		val tree = (scratch.prepare(project) as QuickBuildScratch.Preparation.Ready).dir
		val out = File(tree, "out").apply { mkdirs() }
		val pinned = File(out, "pinned.class").apply { writeText("bytecode") }
		pinShut(out)

		// Teardown has to finish, so a tree that will not go is logged, never propagated.
		scratch.remove(project)

		assertThat(pinned.exists()).isTrue()
		out.setWritable(true)
	}

	@Test
	fun `sweep keeps reclaiming past a tree it cannot delete`() {
		val stuckTree =
			(scratch.prepare(File(projects, "Stuck")) as QuickBuildScratch.Preparation.Ready).dir
		val healthyTree =
			(scratch.prepare(File(projects, "Healthy")) as QuickBuildScratch.Preparation.Ready).dir
		val out = File(stuckTree, "out").apply { mkdirs() }
		val pinned = File(out, "pinned.class").apply { writeText("bytecode") }
		pinShut(out)

		scratch.sweep()

		// A stuck tree costs its own disk and nothing else. Note this pins the OUTCOME, not
		// the iteration order: listFiles() decides which tree is visited first, so a sweep
		// that aborted on the failure would still pass whenever the stuck tree came last.
		assertThat(pinned.exists()).isTrue()
		assertThat(healthyTree.exists()).isFalse()
		out.setWritable(true)
	}
}
