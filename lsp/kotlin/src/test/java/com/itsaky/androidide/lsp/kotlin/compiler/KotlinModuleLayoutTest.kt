package com.itsaky.androidide.lsp.kotlin.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.gradleProject
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.Workspace
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.createFile

class KotlinModuleLayoutTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	@Test
	fun `two workspaces with the same structure have equal layouts`() {
		val jar = tempJar("lib.jar")

		assertThat(layoutOf(compileJars = listOf(jar))).isEqualTo(layoutOf(compileJars = listOf(jar)))
	}

	@Test
	fun `adding a compile jar changes the layout`() {
		val lib = tempJar("lib.jar")
		val added = tempJar("added.jar")

		assertThat(layoutOf(compileJars = listOf(lib, added))).isNotEqualTo(layoutOf(compileJars = listOf(lib)))
	}

	@Test
	fun `the same compile jars in another order are the same layout`() {
		val a = tempJar("a.jar")
		val b = tempJar("b.jar")

		assertThat(layoutOf(compileJars = listOf(b, a))).isEqualTo(layoutOf(compileJars = listOf(a, b)))
	}

	@Test
	fun `a missing jar that appears changes the layout`() {
		val jar = tempDir.root.toPath().resolve("late.jar")
		val before = layoutOf(compileJars = listOf(jar))

		jar.createFile()

		assertThat(layoutOf(compileJars = listOf(jar))).isNotEqualTo(before)
	}

	@Test
	fun `a changed jvm target changes the layout`() {
		assertThat(layoutOf(jvmTarget = "17")).isNotEqualTo(layoutOf(jvmTarget = "11"))
	}

	private fun tempJar(name: String): Path =
		tempDir.root
			.toPath()
			.resolve(name)
			.createFile()

	private fun layoutOf(
		compileJars: List<Path> = emptyList(),
		jvmTarget: String? = null,
	): KotlinModuleLayout =
		Workspace(
			rootProject = GradleProject(gradleProject(":")),
			subProjects = listOf(JavaModule(gradleProject(":app", compileJars = compileJars, jvmTarget = jvmTarget))),
			syncIssues = emptyList(),
		).kotlinModuleLayout()
}
