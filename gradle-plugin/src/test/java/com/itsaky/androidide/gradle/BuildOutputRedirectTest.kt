package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class BuildOutputRedirectTest {
	private val root = File("/out")
	private val projectDir = File("/projects/MyApp")

	@Test
	fun `main build projects go under main`() {
		assertThat(BuildOutputRedirect.buildDirFor(root, false, projectDir, ":")).isEqualTo(File("/out/main/_root"))
		assertThat(BuildOutputRedirect.buildDirFor(root, false, projectDir, ":app")).isEqualTo(File("/out/main/app"))
		assertThat(BuildOutputRedirect.buildDirFor(root, false, projectDir, ":libs:core"))
			.isEqualTo(File("/out/main/libs/core"))
	}

	@Test
	fun `a nested build gets its own directory keyed by its root`() {
		val buildSrc = BuildOutputRedirect.buildDirFor(root, true, File(projectDir, "buildSrc"), ":")

		assertThat(buildSrc.parentFile.parentFile).isEqualTo(root)
		assertThat(buildSrc.parentFile.name).startsWith("buildSrc-")
		assertThat(buildSrc.name).isEqualTo("_root")
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
