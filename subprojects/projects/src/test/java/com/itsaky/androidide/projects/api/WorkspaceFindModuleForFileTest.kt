package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.GradleModels
import org.junit.Test
import java.io.File

class WorkspaceFindModuleForFileTest {
	private val workspace =
		Workspace(
			rootProject =
				GradleProject(
					GradleModels.GradleProject
						.newBuilder()
						.setName("root")
						.setPath(":")
						.build(),
				),
			subProjects = emptyList(),
			syncIssues = emptyList(),
		)

	@Test
	fun `does not stat the file when existence is not checked`() {
		val file = StatCountingFile("/project/app/src/Main.java")

		workspace.findModuleForFile(file, checkExistence = false)

		assertThat(file.existsCalls).isEqualTo(0)
	}

	@Test
	fun `returns null for a missing file when existence is checked`() {
		val file = StatCountingFile("/project/app/src/Main.java")

		assertThat(workspace.findModuleForFile(file, checkExistence = true)).isNull()
		assertThat(file.existsCalls).isEqualTo(1)
	}

	private class StatCountingFile(
		path: String,
	) : File(path) {
		var existsCalls = 0

		override fun exists(): Boolean {
			existsCalls++
			return false
		}
	}
}
