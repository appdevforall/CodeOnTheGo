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
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Functional coverage for the Quick Build setup build (ADFA-4128), run through the shared
 * TestKit harness against the sample project - which enables `viewBinding`, the exact DSL
 * that triggered Bug 1.
 */
class QuickBuildSetupBuildTest {
	/**
	 * ADFA-4128 Bug 1 regression. The setup build runs with `--configuration-cache`; storing
	 * the cache serializes every scheduled task's fields. When `QuickBuildSetupReportTask`
	 * held its source roots as a `ListProperty<String>` mapped from `variant.sources.*.all`,
	 * the store REALIZED that value before any task ran, forcing the viewBinding-contributed
	 * `dataBindingGenBaseClasses<Variant>` output provider and throwing
	 * `InvalidUserCodeException: querying the mapped value ... before task ... completed`.
	 * That failed the store and every viewBinding-enabled setup build (i.e. 7 of 9 built-in
	 * templates). Holding the roots as a `ConfigurableFileCollection` lets the store defer
	 * resolution, so the cache is stored and the report task is scheduled without forcing the
	 * generated-source producers.
	 *
	 * `--dry-run` stops after the store (no compile/dex), so the assertion isolates the
	 * config-cache store step. On the pre-fix code the harness's `.build()` throws, failing
	 * this test.
	 */
	@Test
	fun `viewBinding setup build stores the configuration cache without forcing generated-source providers`() {
		val runtimeAar = File.createTempFile("quickbuild-runtime", ".aar").apply { deleteOnExit() }

		val result =
			buildProject(
				task = ":app:assembleDemoDebug",
				configureArgs = {
					it.add("-P$PROPERTY_QUICK_BUILD_ENABLED=true")
					it.add("-P$PROPERTY_QUICK_BUILD_RUNTIME_AAR=${runtimeAar.absolutePath}")
					it.add("--configuration-cache")
					it.add("--dry-run")
				},
			)

		// The store actually ran (not silently skipped) ...
		assertThat(result.output).contains("Configuration cache entry stored")
		// ... the setup report task WAS scheduled (so its fields were serialized) ...
		assertThat(result.output).contains("writeDemoDebugQuickBuildSetupReport")
		// ... and the pre-fix store failure did not occur.
		assertThat(result.output).doesNotContain("__sourceRoots__")
		assertThat(result.output).doesNotContain("Configuration cache state could not be cached")
	}
}
