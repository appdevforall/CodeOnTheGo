package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallFromCopyTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val plugin: File get() = File(tmp.root, "built.cgp").apply { writeText("cgp") }

	@Test
	fun `the install gets a copy and the built plugin stays`() =
		runTest {
			val source = plugin
			val copy = File(tmp.root, "temp/copy.cgp").apply { parentFile.mkdirs() }
			var seen = ""

			val result =
				installFromCopy(source, copy) {
					seen = it.readText()
					Result.success(Unit)
				}

			assertThat(result.isSuccess).isTrue()
			assertThat(seen).isEqualTo("cgp")
			assertThat(source.isFile).isTrue()
			assertThat(copy.exists()).isFalse()
		}

	@Test
	fun `a failed install leaves no copy behind`() =
		runTest {
			val copy = File(tmp.root, "temp/copy.cgp").apply { parentFile.mkdirs() }

			// The repository fails before it deletes the file it was given, as when the plugin
			// system is unavailable.
			val result = installFromCopy(plugin, copy) { Result.failure(IllegalStateException("Plugin system not available")) }

			assertThat(result.isFailure).isTrue()
			assertThat(copy.exists()).isFalse()
		}

	@Test
	fun `a copy that cannot be made is a failure and leaves no copy behind`() =
		runTest {
			val copy = File(tmp.root, "temp/copy.cgp").apply { parentFile.mkdirs() }
			var installed = false

			val result =
				installFromCopy(File(tmp.root, "missing.cgp"), copy) {
					installed = true
					Result.success(Unit)
				}

			assertThat(result.isFailure).isTrue()
			assertThat(installed).isFalse()
			assertThat(copy.exists()).isFalse()
		}
}
