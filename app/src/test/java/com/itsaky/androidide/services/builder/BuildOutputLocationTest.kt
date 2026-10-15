package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_BUILD_OUTPUT_DIR
import com.itsaky.androidide.utils.ProjectStorageKey
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
	fun `a project never synced with relocation reads as not relocated`() {
		assertThat(BuildOutputLocation.syncedState(File(tmp.root, "project/.cg"))).isFalse()
	}

	@Test
	fun `the synced state round-trips, creating the cache directory`() {
		val cacheDir = File(tmp.root, "project/.cg")

		assertThat(BuildOutputLocation.recordSyncedState(cacheDir, true)).isTrue()
		assertThat(BuildOutputLocation.syncedState(cacheDir)).isTrue()

		assertThat(BuildOutputLocation.recordSyncedState(cacheDir, false)).isTrue()
		assertThat(BuildOutputLocation.syncedState(cacheDir)).isFalse()
	}

	@Test
	fun `recording reports a cache directory it cannot create`() {
		val blocker = tmp.newFile("blocker")

		assertThat(BuildOutputLocation.recordSyncedState(File(blocker, ".cg"), true)).isFalse()
	}

	@Test
	fun `a sync is pending exactly when the request differs from the last sync`() {
		val cacheDir = tmp.newFolder("project", ".cg")

		assertThat(BuildOutputLocation.syncPending(cacheDir, requested = false)).isFalse()
		assertThat(BuildOutputLocation.syncPending(cacheDir, requested = true)).isTrue()

		BuildOutputLocation.recordSyncedState(cacheDir, true)

		assertThat(BuildOutputLocation.syncPending(cacheDir, requested = true)).isFalse()
		assertThat(BuildOutputLocation.syncPending(cacheDir, requested = false)).isTrue()
	}
}
