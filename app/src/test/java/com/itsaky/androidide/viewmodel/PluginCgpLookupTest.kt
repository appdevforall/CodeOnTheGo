package com.itsaky.androidide.viewmodel

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A plugin build's `.cgp` is found under the root project's build directory as the synced model
 * has it. With relocated build output (ADFA-4930) that is in app storage, and `<project>/build`
 * may still hold a plugin from before the relocation.
 */
class PluginCgpLookupTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val project: File get() = File(tmp.root, "project")

	private val relocated: File get() = File(tmp.root, "no_backup/gradle-build/project-1234/main/root-5678")

	@Test
	fun `the plugin is found under a relocated root build directory, not the project folder`() {
		val stale = cgp(File(project, "build/plugin"), "old-debug.cgp")
		val built = cgp(File(relocated, "plugin"), "myplugin-debug.cgp")

		assertThat(findPluginCgpFile(workspace(relocated), "debug")).isEqualTo(built)
		assertThat(findPluginCgpFile(workspace(relocated), "debug")).isNotEqualTo(stale)
	}

	@Test
	fun `a release variant takes the plugin without the debug suffix`() {
		cgp(File(relocated, "plugin"), "myplugin-debug.cgp")
		val release = cgp(File(relocated, "plugin"), "myplugin.cgp")

		assertThat(findPluginCgpFile(workspace(relocated), "release")).isEqualTo(release)
	}

	@Test
	fun `nothing is found without a model or a plugin`() {
		assertThat(findPluginCgpFile(null, "debug")).isNull()
		assertThat(findPluginCgpFile(workspace(relocated), "debug")).isNull()
	}

	private fun cgp(
		dir: File,
		name: String,
	): File = File(dir.apply { mkdirs() }, name).apply { writeText("cgp") }

	private fun workspace(rootBuildDir: File): Workspace {
		val root = mockk<GradleProject>()
		every { root.buildDirPath } returns rootBuildDir.path
		every { root.projectDirPath } returns project.path
		return mockk<Workspace>().also { every { it.rootProject } returns root }
	}
}
