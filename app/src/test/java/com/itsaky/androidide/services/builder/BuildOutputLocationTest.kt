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

	private fun workspace(rootBuildDir: File): Workspace {
		val root = mockk<GradleProject>()
		every { root.buildDirPath } returns rootBuildDir.path
		return mockk<Workspace>().also { every { it.rootProject } returns root }
	}
}
