/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.gradle

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.buildinfo.BuildInfo
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * JVM-pure checks for the root-buildscript classpath the init script plugin injects. That
 * injection is the only thing that lets a subproject resolve the IDE plugin by ID, and
 * injecting a non-existent path is a silent Gradle no-op, so getting this wrong shows up
 * much later as `Plugin with id '...' not found`.
 */
class InitScriptClasspathTest {

	@Test
	fun `uses the bundled jar when the IDE has one`(@TempDir dir: File) {
		val bundled = File(dir, "cogo-plugin.jar").apply { writeText("jar") }
		val other = File(dir, "from-init-script.jar").apply { writeText("jar") }

		val resolved = AndroidIDEInitScriptPlugin.resolvePluginClasspath(bundled, listOf(other))

		assertThat(resolved).containsExactly(bundled)
	}

	@Test
	fun `falls back to the init script classpath when the bundled jar is missing`(
		@TempDir dir: File,
	) {
		val missing = File(dir, "does-not-exist.jar")
		val a = File(dir, "a.jar").apply { writeText("jar") }
		val b = File(dir, "classes").apply { mkdirs() }

		val resolved = AndroidIDEInitScriptPlugin.resolvePluginClasspath(missing, listOf(a, b))

		assertThat(resolved).containsExactly(a, b).inOrder()
	}

	@Test
	fun `drops init script classpath entries that do not exist`(@TempDir dir: File) {
		val missing = File(dir, "does-not-exist.jar")
		val real = File(dir, "real.jar").apply { writeText("jar") }
		val ghost = File(dir, "ghost.jar")

		val resolved =
			AndroidIDEInitScriptPlugin.resolvePluginClasspath(missing, listOf(ghost, real))

		assertThat(resolved).containsExactly(real)
	}

	@Test
	fun `a directory is not mistaken for the bundled jar`(@TempDir dir: File) {
		// isFile, not exists: a directory at the jar path must not be injected as the plugin.
		val bundledAsDir = File(dir, "cogo-plugin.jar").apply { mkdirs() }
		val fallback = File(dir, "a.jar").apply { writeText("jar") }

		val resolved =
			AndroidIDEInitScriptPlugin.resolvePluginClasspath(bundledAsDir, listOf(fallback))

		assertThat(resolved).containsExactly(fallback)
	}

	@Test
	fun `fails loud when there is nothing to inject`(@TempDir dir: File) {
		val missing = File(dir, "does-not-exist.jar")

		val error =
			assertThrows<GradleException> {
				AndroidIDEInitScriptPlugin.resolvePluginClasspath(missing, emptyList())
			}

		assertThat(error).hasMessageThat().contains(BuildInfo.PACKAGE_NAME)
		assertThat(error).hasMessageThat().contains(missing.absolutePath)
	}
}
