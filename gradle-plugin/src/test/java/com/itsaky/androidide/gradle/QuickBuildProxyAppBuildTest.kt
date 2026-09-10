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
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_BASELINE_GENERATION
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
	 * `QuickBuildProxyAppReportTask` must hold its source roots as a `ConfigurableFileCollection`,
	 * which the config-cache store can leave unresolved - never as a `ListProperty<String>` mapped
	 * from `variant.sources.*.all`, whose mapped value the store realizes before any task runs,
	 * forcing viewBinding's `dataBindingGenBaseClasses` provider and failing the store for 7 of the
	 * 9 built-in templates. `--dry-run` stops after the store, so the assertion isolates that step.
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
	 * Proxiability is decided from the variant's dependency class artifacts, so a `final` component
	 * from any library is skipped without anyone naming it (ADFA-4128 followup). Only a real build
	 * proves that wiring that classpath into the task producing the merged manifest creates no task
	 * cycle, and that the `lenient` `ArtifactView` really resolves class bytes - a wrong artifact
	 * type resolves NOTHING silently, so every component would look project-owned. The fixture is
	 * Room-runtime's `MultiInstanceInvalidationService`: `final` in the AAR, named in no source here.
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

	/**
	 * With two flavors the plugin registers a report task per debuggable variant, so a report file
	 * fixed at `build/quickbuild/setup.json` collides: last writer wins, and CoGo installs whichever
	 * flavor finished last under an applicationId suffix the user never selected. `assembleDebug`
	 * is the flavor-agnostic lifecycle task that fans out to both. The declared output is read via
	 * a probe init script because writing a real setup.json needs a real runtime AAR.
	 */
	@Test
	fun `each flavor's proxy app report declares its own variant-scoped setup json`() {
		val runtimeAar = File.createTempFile("quickbuild-runtime", ".aar").apply { deleteOnExit() }
		val probe =
			File.createTempFile("quickbuild-report-probe", ".gradle").apply {
				deleteOnExit()
				writeText(
					"""
					gradle.projectsEvaluated {
						gradle.rootProject.allprojects { p ->
							p.tasks.names.findAll { it.endsWith('QuickBuildProxyAppReport') }.each { n ->
								println "QB-REPORT-PATH ${'$'}{n} -> ${'$'}{p.tasks.getByName(n).reportFile.get().asFile.path}"
							}
						}
					}
					""".trimIndent(),
				)
			}

		val result =
			buildProject(
				task = ":app:assembleDebug",
				configureArgs = {
					it.add("-P$PROPERTY_QUICK_BUILD_ENABLED=true")
					it.add("-P$PROPERTY_QUICK_BUILD_RUNTIME_AAR=${runtimeAar.absolutePath}")
					it.add("--init-script")
					it.add(probe.absolutePath)
					it.add("--dry-run")
				},
			)

		// The lifecycle task really does fan out to both flavors ...
		assertThat(result.output).contains("writeDemoDebugQuickBuildProxyAppReport")
		assertThat(result.output).contains("writeFullDebugQuickBuildProxyAppReport")
		// ... and the two reports do not share a file.
		assertThat(result.output).contains(
			"QB-REPORT-PATH writeDemoDebugQuickBuildProxyAppReport -> ",
		)
		assertThat(result.output).contains("build/quickbuild/demoDebug/setup.json")
		assertThat(result.output).contains("build/quickbuild/fullDebug/setup.json")
		// The single path every variant would collide on.
		assertThat(result.output).doesNotContain("build/quickbuild/setup.json")
	}

	/**
	 * The `-P` baseline-generation property must reach the stamp task's `generation` input, and a
	 * build without the property must stamp 0 (a host older than the stamping change passes no
	 * property; the runtime treats a 0 stamp as its pre-stamp constant baseline). Probed via an
	 * init script under `--dry-run`, like the report-path test above, because writing the real
	 * asset needs a full APK build.
	 */
	@Test
	fun `the baseline generation property threads into the stamp task and defaults to 0`() {
		val runtimeAar = File.createTempFile("quickbuild-runtime", ".aar").apply { deleteOnExit() }
		val probe =
			File.createTempFile("quickbuild-baseline-probe", ".gradle").apply {
				deleteOnExit()
				writeText(
					"""
					gradle.projectsEvaluated {
						gradle.rootProject.allprojects { p ->
							p.tasks.names.findAll { it.endsWith('QuickBuildBaselineGeneration') }.each { n ->
								println "QB-BASELINE-GEN ${'$'}{n} -> ${'$'}{p.tasks.getByName(n).generation.get()}"
							}
						}
					}
					""".trimIndent(),
				)
			}

		val stamped =
			buildProject(
				task = ":app:assembleDemoDebug",
				configureArgs = {
					it.add("-P$PROPERTY_QUICK_BUILD_ENABLED=true")
					it.add("-P$PROPERTY_QUICK_BUILD_RUNTIME_AAR=${runtimeAar.absolutePath}")
					it.add("-P$PROPERTY_QUICK_BUILD_BASELINE_GENERATION=17")
					it.add("--init-script")
					it.add(probe.absolutePath)
					it.add("--dry-run")
				},
			)
		// The stamp task exists, is wired into the variant's assemble, and saw the property.
		assertThat(stamped.output).contains("stampDemoDebugQuickBuildBaselineGeneration")
		assertThat(stamped.output).contains(
			"QB-BASELINE-GEN stampDemoDebugQuickBuildBaselineGeneration -> 17",
		)

		val unstamped =
			buildProject(
				task = ":app:assembleDemoDebug",
				configureArgs = {
					it.add("-P$PROPERTY_QUICK_BUILD_ENABLED=true")
					it.add("-P$PROPERTY_QUICK_BUILD_RUNTIME_AAR=${runtimeAar.absolutePath}")
					it.add("--init-script")
					it.add(probe.absolutePath)
					it.add("--dry-run")
				},
			)
		assertThat(unstamped.output).contains(
			"QB-BASELINE-GEN stampDemoDebugQuickBuildBaselineGeneration -> 0",
		)
	}
}
