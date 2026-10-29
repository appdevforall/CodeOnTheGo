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
	fun `prepare creates the tree and records the canonical project path`() {
		val tree = File(tmp.root, "gradle-build/app-1234")
		val project = File(tmp.root, "projects/app")

		assertThat(BuildOutputLocation.prepare(tree, project)).isTrue()

		assertThat(tree.isDirectory).isTrue()
		assertThat(File(tree, BuildOutputLocation.PROJECT_PATH_FILE).readText()).isEqualTo(project.canonicalPath)
	}

	@Test
	fun `prepare rewrites a stale project path`() {
		val tree = tmp.newFolder("tree")
		File(tree, BuildOutputLocation.PROJECT_PATH_FILE).writeText("/old/place")
		val project = File(tmp.root, "projects/app")

		assertThat(BuildOutputLocation.prepare(tree, project)).isTrue()

		assertThat(File(tree, BuildOutputLocation.PROJECT_PATH_FILE).readText()).isEqualTo(project.canonicalPath)
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
	fun `the open project's model is read through its projects' build directories`() {
		val noBackup = tmp.newFolder("no_backup")
		val project = File(tmp.root, "projects/app")
		val tree = BuildOutputLocation.treeFor(noBackup, project)

		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace(File(tree, "main/root-5678")))).isTrue()
		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace(File(project, "build")))).isFalse()
		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace = null)).isNull()
	}

	@Test
	fun `a model is relocated when only the root sets its own build directory`() {
		val noBackup = tmp.newFolder("no_backup")
		val project = File(tmp.root, "projects/app")
		val tree = BuildOutputLocation.treeFor(noBackup, project)

		// The root script set layout.buildDirectory; :app's output is still in the tree, so builds
		// must relocate too, or :app builds where the model does not look.
		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace(File(project, "out"), File(tree, "main/app-1234"))))
			.isTrue()
		assertThat(BuildOutputLocation.modelRelocated(noBackup, project, workspace(File(project, "out"), File(project, "app/out"))))
			.isFalse()
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
	fun `deleting a project's tree never follows a symlink out of it`() {
		val noBackup = tmp.newFolder("no_backup")
		val project = tmp.newFolder("projects", "app")
		val tree = treeOf(noBackup, project)
		val elsewhere = tmp.newFolder("elsewhere")
		val precious = File(elsewhere, "precious.txt").apply { writeText("keep me") }
		// A build can leave a link in its output, such as one a custom task makes.
		Files.createSymbolicLink(File(tree, "main/app-1234/outputs/link").toPath(), elsewhere.toPath())

		assertThat(BuildOutputLocation.removeTreesOf(noBackup, project)).isEqualTo(1)

		assertThat(tree.exists()).isFalse()
		assertThat(precious.readText()).isEqualTo("keep me")
	}

	@Test
	fun `the sweep does not follow a link among the trees`() {
		val noBackup = tmp.newFolder("no_backup")
		// A folder outside gradle-build whose marker names a deleted project, reached through a link.
		val elsewhere = tmp.newFolder("elsewhere")
		File(elsewhere, BuildOutputLocation.PROJECT_PATH_FILE).writeText(File(tmp.newFolder("projects"), "gone").path)
		val precious = File(elsewhere, "precious.txt").apply { writeText("keep me") }
		Files.createSymbolicLink(File(File(noBackup, BuildOutputLocation.DIR_NAME).apply { mkdirs() }, "link").toPath(), elsewhere.toPath())

		assertThat(BuildOutputLocation.sweep(noBackup, tmp.root)).isEqualTo(0)

		assertThat(precious.readText()).isEqualTo("keep me")
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
	fun `deleting a project opened through a symlinked root deletes its tree`() {
		val noBackup = tmp.newFolder("no_backup")
		val real = tmp.newFolder("real", "app")
		val alias = File(tmp.root, "alias-app").also { Files.createSymbolicLink(it.toPath(), real.toPath()) }
		val tree = treeOf(noBackup, alias)
		// Recents deletes the real folder, which leaves the alias dangling.
		real.deleteRecursively()

		assertThat(BuildOutputLocation.removeTreesOf(noBackup, real)).isEqualTo(1)
		assertThat(tree.exists()).isFalse()
	}

	@Test
	fun `the sweep deletes the trees of deleted projects only`() {
		val noBackup = tmp.newFolder("no_backup")
		val kept = tmp.newFolder("projects", "kept")
		val deleted = tmp.newFolder("projects", "deleted")
		val keptTree = treeOf(noBackup, kept)
		val deletedTree = treeOf(noBackup, deleted)
		val volumes = tmp.newFolder("storage")
		val unmountedTree = treeOf(noBackup, File(volumes, "usb-drive/Projects/app"))
		val markerless = File(noBackup, "${BuildOutputLocation.DIR_NAME}/no-marker").apply { mkdirs() }
		deleted.deleteRecursively()

		assertThat(BuildOutputLocation.sweep(noBackup, volumes)).isEqualTo(1)

		assertThat(deletedTree.exists()).isFalse()
		assertThat(keptTree.isDirectory).isTrue()
		// Only the volumes root is left of the path: the volume is not mounted, not deleted.
		assertThat(unmountedTree.isDirectory).isTrue()
		assertThat(markerless.isDirectory).isTrue()
	}

	@Test
	fun `the sweep deletes the trees of projects deleted with their folder`() {
		val noBackup = tmp.newFolder("no_backup")
		val volumes = tmp.newFolder("storage")
		val experiments = tmp.newFolder("storage", "emulated", "Projects", "Experiments")
		val first = treeOf(noBackup, File(experiments, "first").apply { mkdirs() })
		val second = treeOf(noBackup, File(experiments, "nested/second").apply { mkdirs() })
		val outsideVolumes = treeOf(noBackup, File(tmp.root, "elsewhere/gone/app"))
		experiments.deleteRecursively()

		assertThat(BuildOutputLocation.sweep(noBackup, volumes)).isEqualTo(2)

		assertThat(first.exists()).isFalse()
		assertThat(second.exists()).isFalse()
		// Off the volumes, a missing parent proves nothing, so the tree stays.
		assertThat(outsideVolumes.isDirectory).isTrue()
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

	private fun workspace(
		rootBuildDir: File,
		vararg subprojectBuildDirs: File,
	): Workspace {
		fun project(buildDir: File) = mockk<GradleProject>().also { every { it.buildDirPath } returns buildDir.path }
		return mockk<Workspace>().also {
			every { it.rootProject } returns project(rootBuildDir)
			every { it.subProjects } returns subprojectBuildDirs.map(::project)
		}
	}
}
