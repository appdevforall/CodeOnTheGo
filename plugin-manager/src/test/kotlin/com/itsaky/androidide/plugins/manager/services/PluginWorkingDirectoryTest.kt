package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginWorkingDirectoryTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun resolve(
		projectRoot: File?,
		workingDirectory: String?,
	) = resolvePluginWorkingDirectory("test.plugin", projectRoot, workingDirectory)

	@Test
	fun nullMeansTheProjectRoot() {
		assertThat(resolve(tmp.root, null)).isEqualTo(tmp.root)
	}

	@Test
	fun relativePathIsTakenAgainstTheProjectRoot() {
		assertThat(resolve(tmp.root, "app/src")).isEqualTo(File(tmp.root, "app/src").canonicalFile)
	}

	@Test
	fun absolutePathInsideTheProjectIsAllowed() {
		val inside = File(tmp.root, "app").absolutePath

		assertThat(resolve(tmp.root, inside)).isEqualTo(File(inside))
	}

	@Test(expected = SecurityException::class)
	fun relativePathEscapingTheProjectIsRejected() {
		resolve(tmp.newFolder("project"), "../elsewhere")
	}

	@Test(expected = SecurityException::class)
	fun absolutePathOutsideTheProjectIsRejected() {
		resolve(tmp.newFolder("project"), tmp.root.absolutePath)
	}

	@Test
	fun withoutAProjectTheDefaultDirectoryIsUsed() {
		assertThat(resolve(null, null)).isNull()
	}

	@Test(expected = SecurityException::class)
	fun withoutAProjectAnAbsolutePathIsRejected() {
		resolve(null, tmp.root.absolutePath)
	}

	@Test(expected = SecurityException::class)
	fun withoutAProjectARelativePathIsRejected() {
		resolve(null, "app")
	}
}
