package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectWriterPackageNameTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun dir(path: String) = File(tmp.root, path).apply { mkdirs() }

	@Test
	fun `a source root follows lone package directories down to the full package`() {
		dir("app/src/main/kotlin/org/fossify/math/activities")
		dir("app/src/main/kotlin/org/fossify/math/views")
		assertThat(ProjectWriter.getPackageName(dir("app/src/main/kotlin"))).isEqualTo("org.fossify.math")
	}

	@Test
	fun `a source root stops where a directory also holds source files`() {
		dir("app/src/main/java/com/example/ui")
		File(dir("app/src/main/java/com/example"), "MainActivity.java").createNewFile()
		assertThat(ProjectWriter.getPackageName(dir("app/src/main/java"))).isEqualTo("com.example")
	}

	@Test
	fun `a source root ignores files that are not sources`() {
		File(dir("app/src/main/java"), ".gitkeep").createNewFile()
		File(dir("app/src/main/java/com/example/app"), ".DS_Store").createNewFile()
		File(dir("app/src/main/java/com"), "README.md").createNewFile()
		assertThat(ProjectWriter.getPackageName(dir("app/src/main/java"))).isEqualTo("com.example.app")
	}

	@Test
	fun `a source root with several top-level packages has no inferred package`() {
		dir("app/src/main/java/com")
		dir("app/src/main/java/org")
		assertThat(ProjectWriter.getPackageName(dir("app/src/main/java"))).isEmpty()
	}

	@Test
	fun `a package directory maps to its path below the source root`() {
		assertThat(ProjectWriter.getPackageName(dir("app/src/main/kotlin/com/ktor"))).isEqualTo("com.ktor")
	}
}
