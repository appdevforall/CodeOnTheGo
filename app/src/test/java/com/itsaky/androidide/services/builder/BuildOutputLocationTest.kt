package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_BUILD_OUTPUT_DIR
import com.itsaky.androidide.utils.ProjectStorageKey
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class BuildOutputLocationTest {
	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `tree is keyed like Quick Build's scratch, under gradle-build`() {
		val noBackup = tmp.newFolder("no_backup")
		val project = File(tmp.root, "projects/My App")

		val tree = BuildOutputLocation.treeFor(noBackup, project)

		assertThat(tree).isEqualTo(File(File(noBackup, "gradle-build"), ProjectStorageKey.of(project)))
	}

	@Test
	fun `args relocate build dirs, the project cache and kotlin state into the tree`() {
		val tree = File(tmp.root, "tree")

		assertThat(BuildOutputLocation.gradleArgs(tree))
			.containsExactly(
				"-P$PROPERTY_BUILD_OUTPUT_DIR=${tree.absolutePath}",
				"--project-cache-dir",
				File(tree, "project-cache").absolutePath,
				"-Pkotlin.project.persistent.dir=${File(tree, "kotlin").absolutePath}",
				"--no-watch-fs",
			).inOrder()
	}

	@Test
	fun `watch-fs is dropped wherever it sits once the project cache is relocated`() {
		val relocation = BuildOutputLocation.gradleArgs(File(tmp.root, "tree"))

		for (args in listOf(listOf("--parallel", "--watch-fs") + relocation, relocation + "--watch-fs")) {
			assertThat(BuildOutputLocation.withoutWatchConflict(args)).doesNotContain("--watch-fs")
			assertThat(BuildOutputLocation.withoutWatchConflict(args)).containsAtLeastElementsIn(relocation)
		}
	}

	@Test
	fun `watch-fs is kept when nothing is relocated`() {
		val args = listOf("--parallel", "--watch-fs")

		assertThat(BuildOutputLocation.withoutWatchConflict(args)).isEqualTo(args)
	}

	@Test
	fun `prepare creates the tree and records the project path`() {
		val tree = File(tmp.root, "gradle-build/app-1234")
		val project = File(tmp.root, "projects/app")

		assertThat(BuildOutputLocation.prepare(tree, project)).isTrue()

		assertThat(tree.isDirectory).isTrue()
		assertThat(File(tree, BuildOutputLocation.PROJECT_PATH_FILE).readText()).isEqualTo(project.absolutePath)
	}

	@Test
	fun `prepare rewrites a stale project path`() {
		val tree = tmp.newFolder("tree")
		File(tree, BuildOutputLocation.PROJECT_PATH_FILE).writeText("/old/place")
		val project = File(tmp.root, "projects/app")

		assertThat(BuildOutputLocation.prepare(tree, project)).isTrue()

		assertThat(File(tree, BuildOutputLocation.PROJECT_PATH_FILE).readText()).isEqualTo(project.absolutePath)
	}

	@Test
	fun `prepare reports a tree it cannot create`() {
		val blocker = tmp.newFile("blocker")

		assertThat(BuildOutputLocation.prepare(File(blocker, "tree"), File(tmp.root, "app"))).isFalse()
	}

	@Test
	fun `prepare reports a marker it cannot write instead of throwing`() {
		val tree = tmp.newFolder("tree")
		File(tree, BuildOutputLocation.PROJECT_PATH_FILE).mkdir()

		assertThat(BuildOutputLocation.prepare(tree, File(tmp.root, "app"))).isFalse()
	}

	@Test
	fun `a model whose build directory is in the tree is relocated`() {
		val tree = File(tmp.root, "no_backup/gradle-build/app-1234")

		assertThat(BuildOutputLocation.isRelocated(File(tree, "main/root-5678"), tree)).isTrue()
	}

	@Test
	fun `a model building into the project folder is not relocated`() {
		val tree = File(tmp.root, "no_backup/gradle-build/app-1234")

		assertThat(BuildOutputLocation.isRelocated(File(tmp.root, "projects/app/build"), tree)).isFalse()
	}

	@Test
	fun `a sibling tree that shares the name as a prefix is not this project's`() {
		val tree = File(tmp.root, "no_backup/gradle-build/app-1234")
		val sibling = File(tmp.root, "no_backup/gradle-build/app-12345/main/root-5678")

		assertThat(BuildOutputLocation.isRelocated(sibling, tree)).isFalse()
	}

	@Test
	fun `the open project's model is read through its root project's build directory`() {
		val noBackup = tmp.newFolder("no_backup")
		val project = File(tmp.root, "projects/app")
		val tree = BuildOutputLocation.treeFor(noBackup, project)

		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace(File(tree, "main/root-5678")))).isTrue()
		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace(File(project, "build")))).isFalse()
		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace = null)).isNull()
	}

	@Test
	fun `a sync is pending exactly when the request differs from the synced model`() {
		assertThat(BuildOutputLocation.syncPending(modelRelocated = false, requested = false)).isFalse()
		assertThat(BuildOutputLocation.syncPending(modelRelocated = false, requested = true)).isTrue()
		assertThat(BuildOutputLocation.syncPending(modelRelocated = true, requested = true)).isFalse()
		assertThat(BuildOutputLocation.syncPending(modelRelocated = true, requested = false)).isTrue()
	}

	@Test
	fun `nothing is pending before a model is loaded`() {
		assertThat(BuildOutputLocation.syncPending(modelRelocated = null, requested = true)).isFalse()
	}

	@Test
	fun `deleting a project deletes its tree and no other`() {
		val noBackup = tmp.newFolder("no_backup")
		val app = tmp.newFolder("projects", "app")
		val other = tmp.newFolder("projects", "other")
		val appTree = treeOf(noBackup, app)
		val otherTree = treeOf(noBackup, other)

		assertThat(BuildOutputLocation.removeTreesOf(noBackup, app)).isEqualTo(1)

		assertThat(appTree.exists()).isFalse()
		assertThat(otherTree.isDirectory).isTrue()
	}

	@Test
	fun `a tree recorded under another spelling of the project path is still found`() {
		val noBackup = tmp.newFolder("no_backup")
		val app = tmp.newFolder("storage", "app")
		val link = File(tmp.root, "sdcard").toPath()
		Files.createSymbolicLink(link, app.parentFile.toPath())
		val tree = treeOf(noBackup, File(link.toFile(), "app"))

		assertThat(BuildOutputLocation.removeTreesOf(noBackup, app)).isEqualTo(1)
		assertThat(tree.exists()).isFalse()
	}

	@Test
	fun `the sweep deletes the trees of deleted projects only`() {
		val noBackup = tmp.newFolder("no_backup")
		val kept = tmp.newFolder("projects", "kept")
		val deleted = tmp.newFolder("projects", "deleted")
		val keptTree = treeOf(noBackup, kept)
		val deletedTree = treeOf(noBackup, deleted)
		val unmountedTree = treeOf(noBackup, File(tmp.root, "unmounted-volume/app"))
		val markerless = File(noBackup, "${BuildOutputLocation.DIR_NAME}/no-marker").apply { mkdirs() }
		deleted.deleteRecursively()

		assertThat(BuildOutputLocation.sweep(noBackup)).isEqualTo(1)

		assertThat(deletedTree.exists()).isFalse()
		assertThat(keptTree.isDirectory).isTrue()
		// A missing parent reads as a volume that is not mounted, not as a deleted project.
		assertThat(unmountedTree.isDirectory).isTrue()
		assertThat(markerless.isDirectory).isTrue()
	}

	@Test
	fun `a deletion that stops part-way keeps the marker for the next sweep`() {
		val noBackup = tmp.newFolder("no_backup")
		val project = tmp.newFolder("projects", "app")
		val tree = treeOf(noBackup, project)
		val locked = File(tree, "main/app-1234/outputs").apply { File(this, "app-debug.apk").writeText("apk") }
		locked.setWritable(false)
		try {
			assertThat(BuildOutputLocation.removeTreesOf(noBackup, project)).isEqualTo(0)

			assertThat(File(tree, BuildOutputLocation.PROJECT_PATH_FILE).isFile).isTrue()
		} finally {
			locked.setWritable(true)
		}
		assertThat(BuildOutputLocation.removeTreesOf(noBackup, project)).isEqualTo(1)
		assertThat(tree.exists()).isFalse()
	}

	@Test
	fun `the sweep copes with no trees at all`() {
		assertThat(BuildOutputLocation.sweep(tmp.newFolder("no_backup"))).isEqualTo(0)
	}

	private fun treeOf(
		noBackup: File,
		project: File,
	): File =
		BuildOutputLocation.treeFor(noBackup, project).also {
			assertThat(BuildOutputLocation.prepare(it, project)).isTrue()
			File(it, "main/app-1234/outputs").mkdirs()
		}

	@Test
	fun `every spelling of a project's path names the same tree`() {
		val noBackup = tmp.newFolder("no_backup")
		val app = tmp.newFolder("storage", "app")
		val link = File(tmp.root, "sdcard").toPath()
		Files.createSymbolicLink(link, app.parentFile.toPath())

		assertThat(BuildOutputLocation.treeFor(noBackup, File(link.toFile(), "app")))
			.isEqualTo(BuildOutputLocation.treeFor(noBackup, app))
	}

	@Test
	fun `the tree the loaded model builds into is never deleted`() {
		val noBackup = tmp.newFolder("no_backup")
		val project = tmp.newFolder("projects", "app")
		val tree = treeOf(noBackup, project)

		assertThat(BuildOutputLocation.removeTreesOf(noBackup, project, keep = File(tree, "main/root-5678"))).isEqualTo(0)
		assertThat(tree.isDirectory).isTrue()

		assertThat(BuildOutputLocation.removeTreesOf(noBackup, project, keep = File(project, "build"))).isEqualTo(1)
		assertThat(tree.exists()).isFalse()
	}

	@Test
	fun `trees are deleted only after an initialization whose model builds into the project folder`() {
		assertThat(BuildOutputLocation.shouldDeleteTrees(afterInit = true, modelRelocated = false, quickBuildLive = false)).isTrue()
		assertThat(BuildOutputLocation.shouldDeleteTrees(afterInit = false, modelRelocated = false, quickBuildLive = false)).isFalse()
		assertThat(BuildOutputLocation.shouldDeleteTrees(afterInit = true, modelRelocated = true, quickBuildLive = false)).isFalse()
		assertThat(BuildOutputLocation.shouldDeleteTrees(afterInit = true, modelRelocated = null, quickBuildLive = false)).isFalse()
	}

	@Test
	fun `a live Quick Build session keeps the tree it still uses`() {
		assertThat(BuildOutputLocation.shouldDeleteTrees(afterInit = true, modelRelocated = false, quickBuildLive = true)).isFalse()
	}

	@Test
	fun `a deferred sync is offered again only once no build holds the slot`() {
		assertThat(BuildOutputLocation.shouldOfferDeferredSync(building = false, deferred = true, slotBusy = false)).isTrue()
		assertThat(BuildOutputLocation.shouldOfferDeferredSync(building = true, deferred = true, slotBusy = true)).isFalse()
		// The user-visible flag dropped, but Quick Build's prebuild still holds the slot.
		assertThat(BuildOutputLocation.shouldOfferDeferredSync(building = false, deferred = true, slotBusy = true)).isFalse()
		assertThat(BuildOutputLocation.shouldOfferDeferredSync(building = false, deferred = false, slotBusy = false)).isFalse()
	}

	private fun workspace(rootBuildDir: File): Workspace {
		val root = mockk<GradleProject>()
		every { root.buildDirPath } returns rootBuildDir.path
		return mockk<Workspace>().also { every { it.rootProject } returns root }
	}
}
