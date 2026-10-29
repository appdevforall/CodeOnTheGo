package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class BuildOutputRedirectTest {
	private val root = File("/out")
	private val projectDir = File("/projects/MyApp")

	private fun main(path: String) = BuildOutputRedirect.buildDirFor(root, false, projectDir, path)

	@Test
	fun `main build projects get one flat directory each under main`() {
		for (path in listOf(":", ":app", ":libs:core")) {
			assertThat(main(path).parentFile).isEqualTo(File(root, "main"))
		}
		assertThat(main(":").name).startsWith("root-")
		assertThat(main(":app").name).startsWith("app-")
		assertThat(main(":libs:core").name).startsWith("core-")
	}

	@Test
	fun `a child project's build directory is not inside its parent's`() {
		val parent = main(":core")
		val child = main(":core:ui")

		assertThat(child.parentFile).isEqualTo(parent.parentFile)
		assertThat(child.path).doesNotContain(parent.path + File.separator)
	}

	@Test
	fun `names that used to collide do not`() {
		val dirs = listOf(":", ":_root", ":root", ":core", ":core:generated", ":generated", ":a:b", ":a_b").map(::main)

		assertThat(dirs.toSet()).hasSize(dirs.size)
	}

	@Test
	fun `the mapping is stable and never contains a colon`() {
		assertThat(main(":core:ui")).isEqualTo(main(":core:ui"))
		assertThat(main(":core:ui").name).doesNotContain(":")
	}

	@Test
	fun `a nested build gets its own directory keyed by its root`() {
		val buildSrc = BuildOutputRedirect.buildDirFor(root, true, File(projectDir, "buildSrc"), ":")

		assertThat(buildSrc.parentFile.parentFile).isEqualTo(root)
		assertThat(buildSrc.parentFile.name).startsWith("buildSrc-")
		assertThat(buildSrc.name).startsWith("root-")
	}

	@Test
	fun `nested builds with the same name in different places do not collide`() {
		val a = BuildOutputRedirect.buildDirFor(root, true, File("/a/plugins"), ":app")
		val b = BuildOutputRedirect.buildDirFor(root, true, File("/b/plugins"), ":app")
		val again = BuildOutputRedirect.buildDirFor(root, true, File("/a/plugins"), ":app")

		assertThat(a).isNotEqualTo(b)
		assertThat(again).isEqualTo(a)
	}
}
