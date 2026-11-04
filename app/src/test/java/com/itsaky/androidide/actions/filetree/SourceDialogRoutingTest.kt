package com.itsaky.androidide.actions.filetree

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceDialogRoutingTest {
	private val projectDir = "/storage/emulated/0/CodeOnTheGoProjects/MyApp"

	private fun route(relativePath: String) = sourceDialogFor(projectDir, "$projectDir/$relativePath")

	@Test
	fun `a cpp source root and its subfolders open the native dialog`() {
		assertThat(route("app/src/main/cpp")).isEqualTo(SourceDialog.CPP)
		assertThat(route("app/src/debug/cpp/engine")).isEqualTo(SourceDialog.CPP)
	}

	@Test
	fun `a cpp subfolder whose name contains java still opens the native dialog`() {
		assertThat(route("app/src/main/cpp/javabridge")).isEqualTo(SourceDialog.CPP)
	}

	@Test
	fun `a java source folder opens the class dialog`() {
		assertThat(route("app/src/main/java")).isEqualTo(SourceDialog.JAVA)
		assertThat(route("app/src/main/java/com/example")).isEqualTo(SourceDialog.JAVA)
		assertThat(route("app/src/main/java/cpp")).isEqualTo(SourceDialog.JAVA)
	}

	@Test
	fun `other folders open neither source dialog`() {
		assertThat(route("app/src/main/res")).isNull()
		assertThat(route("app/src/main/cppx")).isNull()
	}
}
