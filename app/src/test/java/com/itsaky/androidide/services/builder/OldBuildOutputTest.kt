package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class OldBuildOutputTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun dirs(
		root: File,
		vararg paths: String,
	): List<File> = paths.map { File(root, it).apply { mkdirs() } }

	@Test
	fun `finds each relocated project's build folder and the Gradle and Kotlin caches`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		dirs(root, "build", "app/build", ".gradle", ".kotlin", "buildSrc/build", "buildSrc/.kotlin", "buildSrc/.gradle", ".cxx")

		val found = OldBuildOutput.find(root, tree, listOf(root, File(root, "app")), listOf(File(tree, "root"), File(tree, "app")))

		assertThat(found.map { it.relativeTo(root).path }).containsExactly(
			"build",
			"app/build",
			".gradle",
			".kotlin",
			"buildSrc/.kotlin",
		)
	}

	@Test
	fun `a module called build is not build output`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		val module = dirs(root, "build/src").first().parentFile

		val found = OldBuildOutput.find(root, tree, listOf(root, module), listOf(File(tree, "root"), File(tree, "build")))

		assertThat(found).isEmpty()
	}

	@Test
	fun `a build folder the model still builds into is kept`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		dirs(root, "build/custom", "app/build")

		// The root project set its own build directory, overriding the relocation.
		val found = OldBuildOutput.find(root, tree, listOf(root, File(root, "app")), listOf(File(root, "build/custom"), File(tree, "app")))

		assertThat(found.map { it.relativeTo(root).path }).containsExactly("app/build")
	}

	@Test
	fun `folders outside the project and symlinked folders are kept`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		val outside = tmp.newFolder("shared", "lib")
		File(outside, "build").mkdirs()
		val elsewhere = tmp.newFolder("elsewhere")
		Files.createSymbolicLink(File(root, "build").toPath(), elsewhere.toPath())

		val found = OldBuildOutput.find(root, tree, listOf(root, outside), listOf(File(tree, "root"), File(tree, "lib")))

		assertThat(found).isEmpty()
	}

	@Test
	fun `deleting never follows a symlink out of the folder`() {
		val build = tmp.newFolder("project", "build")
		File(build, "outputs/apk").mkdirs()
		File(build, "outputs/apk/app-debug.apk").writeText("apk")
		val elsewhere = tmp.newFolder("elsewhere")
		val precious = File(elsewhere, "precious.txt").apply { writeText("keep me") }
		Files.createSymbolicLink(File(build, "link").toPath(), elsewhere.toPath())

		assertThat(deleteWithoutFollowingLinks(build)).isTrue()

		assertThat(build.exists()).isFalse()
		assertThat(precious.readText()).isEqualTo("keep me")
	}

	@Test
	fun `old output is removed once`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		dirs(root, "app/build/outputs", ".gradle")
		val projects = listOf(root, File(root, "app"))
		val buildDirs = listOf(File(tree, "root"), File(tree, "app"))

		assertThat(OldBuildOutput.removeOnce(tree, root, projects, buildDirs)).isEqualTo(2)
		assertThat(File(root, "app/build").exists()).isFalse()
		assertThat(File(root, ".gradle").exists()).isFalse()
		assertThat(File(root, "app").isDirectory).isTrue()

		// A later build from the terminal writes here again; that output is the user's to keep.
		dirs(root, "app/build")
		assertThat(OldBuildOutput.removeOnce(tree, root, projects, buildDirs)).isEqualTo(0)
		assertThat(File(root, "app/build").isDirectory).isTrue()
	}

	@Test
	fun `a build folder reached through a symlinked module folder is kept`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		val shared = tmp.newFolder("shared", "app")
		File(shared, "build/outputs").mkdirs()
		// The module's folder is a link out of the project; its build folder itself is real.
		Files.createSymbolicLink(File(root, "app").toPath(), shared.toPath())

		val found = OldBuildOutput.find(root, tree, listOf(root, File(root, "app")), listOf(File(tree, "root"), File(tree, "app")))

		assertThat(found).isEmpty()
		assertThat(File(shared, "build/outputs").isDirectory).isTrue()
	}

	@Test
	fun `a deletion that fails is tried again at the next initialization`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		dirs(root, "app/build/outputs", ".gradle")
		val locked = File(root, "app/build/outputs").apply { File(this, "app-debug.apk").writeText("apk") }
		val projects = listOf(root, File(root, "app"))
		val buildDirs = listOf(File(tree, "root"), File(tree, "app"))

		locked.setWritable(false)
		try {
			assertThat(OldBuildOutput.removeOnce(tree, root, projects, buildDirs)).isEqualTo(1)
			assertThat(File(tree, OldBuildOutput.REMOVED_FILE).exists()).isFalse()
		} finally {
			locked.setWritable(true)
		}
		assertThat(OldBuildOutput.removeOnce(tree, root, projects, buildDirs)).isEqualTo(1)
		assertThat(File(root, "app/build").exists()).isFalse()
		assertThat(File(tree, OldBuildOutput.REMOVED_FILE).exists()).isTrue()
	}

	@Test
	fun `a build folder in a project with its own build directory is the user's`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		dirs(root, "app/build", "lib/out", "lib/build")
		val notes = File(root, "lib/build/notes.txt").apply { writeText("mine") }
		val projects = listOf(root, File(root, "app"), File(root, "lib"))
		// :lib sets layout.buildDirectory = file("out"), which overrides the relocation.
		val buildDirs = listOf(File(tree, "root"), File(tree, "app"), File(root, "lib/out"))

		assertThat(OldBuildOutput.find(root, tree, projects, buildDirs).map { it.relativeTo(root).path })
			.containsExactly("app/build")
		OldBuildOutput.removeOnce(tree, root, projects, buildDirs)
		assertThat(notes.readText()).isEqualTo("mine")
	}

	@Test
	fun `buildSrc's build folder is kept, as the model cannot show where buildSrc builds`() {
		val root = tmp.newFolder("project")
		val tree = tmp.newFolder("tree")
		// buildSrc may set its own build directory, such as buildSrc/out; its build/ is then the user's.
		dirs(root, "buildSrc/out", "buildSrc/build", "app/build")
		val notes = File(root, "buildSrc/build/notes.txt").apply { writeText("mine") }
		val projects = listOf(root, File(root, "app"))
		val buildDirs = listOf(File(tree, "root"), File(tree, "app"))

		assertThat(OldBuildOutput.find(root, tree, projects, buildDirs).map { it.relativeTo(root).path })
			.containsExactly("app/build")
		OldBuildOutput.removeOnce(tree, root, projects, buildDirs)
		assertThat(notes.readText()).isEqualTo("mine")
	}
}
