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
 * Functional coverage for the Quick Build proxy app build (ADFA-4128), run through the shared
 * TestKit harness against the sample project - which enables `viewBinding`, the DSL that
 * makes generated-source providers part of the configuration-cache store.
 */
class QuickBuildProxyAppBuildTest {
	/**
	 * The proxy app build runs with `--configuration-cache`, and storing the cache serializes
	 * every scheduled task's fields. `QuickBuildProxyAppReportTask` must therefore hold its
	 * source roots as a `ConfigurableFileCollection`, which the store can leave unresolved -
	 * never as a `ListProperty<String>` mapped from `variant.sources.*.all`, whose mapped value
	 * the store realizes before any task runs. Realizing it forces the viewBinding-contributed
	 * `dataBindingGenBaseClasses<Variant>` output provider and throws
	 * `InvalidUserCodeException: querying the mapped value ... before task ... completed`, which
	 * fails the store and with it every viewBinding-enabled proxy app build - 7 of the 9
	 * built-in templates.
	 *
	 * `--dry-run` stops after the store (no compile/dex), so the assertion isolates the
	 * config-cache store step.
	 */
	@Test
	fun `viewBinding proxy app build stores the configuration cache without forcing generated-source providers`() {
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
		// ... the proxy app report task WAS scheduled (so its fields were serialized) ...
		assertThat(result.output).contains("writeDemoDebugQuickBuildProxyAppReport")
		// ... and the store did not trip over a realized source-roots provider.
		assertThat(result.output).doesNotContain("__sourceRoots__")
		assertThat(result.output).doesNotContain("Configuration cache state could not be cached")
	}

	/**
	 * The manifest transform decides proxiability from the variant's DEPENDENCY class
	 * artifacts, so a `final` component from ANY library is skipped without anyone naming it
	 * (ADFA-4128 followup). Two things can only be proven by a real build, and both are the
	 * risk in that change:
	 *
	 * - Wiring a classpath into the task that PRODUCES the merged manifest is what cycled
	 *   before; a circular task dependency fails this build outright.
	 * - The `ArtifactView` really resolves class bytes. It is `lenient`, so a wrong artifact
	 *   type would silently resolve NOTHING, every component would look project-owned, and
	 *   Compose/Room projects would go back to failing the proxy compile. The fixture is real:
	 *   the sample project depends on room-runtime, whose merged-in
	 *   `MultiInstanceInvalidationService` is genuinely `final` in the shipped AAR - and is
	 *   named nowhere in Quick Build's source.
	 */
	@Test
	fun `a final component from a real dependency is skipped, read from that dependency's class bytes`() {
		val runtimeAar = File.createTempFile("quickbuild-runtime", ".aar").apply { deleteOnExit() }

		val result =
			buildProject(
				task = ":app:generateDemoDebugQuickBuildSources",
				configureArgs = {
					it.add("-P$PROPERTY_QUICK_BUILD_ENABLED=true")
					it.add("-P$PROPERTY_QUICK_BUILD_RUNTIME_AAR=${runtimeAar.absolutePath}")
				},
			)

		assertThat(result.output).contains(
			"Quick Build: 'androidx.room.MultiInstanceInvalidationService' keeps its real manifest name, unproxied",
		)
		// From the class file's access flags, not from a name: the reason distinguishes the two.
		assertThat(result.output).contains("final class - cannot be extended")
	}
}
