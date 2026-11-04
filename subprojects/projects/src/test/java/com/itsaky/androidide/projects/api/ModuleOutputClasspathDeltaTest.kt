package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import io.mockk.every
import io.mockk.spyk
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the classpath delta the module-output symbol index has to cover.
 *
 * Per-module class lookup used to cover `getCompileClasspaths()`, while the library index covers
 * `getCompileClasspaths(excludeSourceGeneratedClassPath = true)`. The difference must be exactly
 * the generated JARs of the module and of every project module it compiles against, and never
 * `R.jar`, which both variants already contain. These characterise existing behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ModuleOutputClasspathDeltaTest {
	@After
	fun tearDown() {
		runCatching { ProjectManagerImpl.getInstance().workspace = null }
	}

	@Test
	fun `the delta of a standalone module is its own generated jar`() {
		val a = androidModule(":a")
		installWorkspace(a)

		assertThat(a.classpathDelta()).containsExactly(generatedJarOf(":a"))
	}

	@Test
	fun `the delta includes the generated jar of a project dependency`() {
		val a = androidModule(":a", moduleDeps = listOf(":b"))
		val b = androidModule(":b")
		installWorkspace(a, b)

		assertThat(a.classpathDelta()).containsExactly(generatedJarOf(":a"), generatedJarOf(":b"))
	}

	@Test
	fun `the delta reaches transitively through project dependencies`() {
		val a = androidModule(":a", moduleDeps = listOf(":b"))
		val b = androidModule(":b", moduleDeps = listOf(":c"))
		val c = androidModule(":c")
		installWorkspace(a, b, c)

		assertThat(a.classpathDelta())
			.containsExactly(generatedJarOf(":a"), generatedJarOf(":b"), generatedJarOf(":c"))
	}

	@Test
	fun `an external jar is not part of the delta`() {
		val a = androidModule(":a", externalJars = listOf("$ROOT/ext/guava.jar"))
		installWorkspace(a)

		assertThat(a.classpathDelta()).containsExactly(generatedJarOf(":a"))
	}

	@Test
	fun `the delta is the generated jars of the compile module closure and holds no R jar`() {
		val a = androidModule(":a", moduleDeps = listOf(":b"), externalJars = listOf("$ROOT/ext/guava.jar"))
		val b = androidModule(":b", moduleDeps = listOf(":c"))
		val c = androidModule(":c")
		installWorkspace(a, b, c)

		val closure = listOf(a) + a.getCompileModuleProjects().filterIsInstance<AndroidModule>()
		val delta = a.classpathDelta()

		assertThat(delta).isEqualTo(closure.map { it.getGeneratedJar() }.toSet())
		assertThat(a.getCompileClasspaths(false)).containsAtLeast(rJarOf(":a"), rJarOf(":b"), rJarOf(":c"))
		assertThat(delta.map { it.name }).doesNotContain("R.jar")
	}

	private fun ModuleProject.classpathDelta(): Set<File> = getCompileClasspaths(false) - getCompileClasspaths(true)

	private fun generatedJarOf(path: String) = File(moduleDir(path), "build/classes.jar")

	private fun rJarOf(path: String) = File(moduleDir(path), "build/R.jar")

	private fun moduleDir(path: String) = File("$ROOT${path.replace(':', '/')}")

	/**
	 * Builds an [AndroidModule] whose compile graph lists [moduleDeps] as project libraries and
	 * [externalJars] as external Java libraries, with an `R.jar` on its selected variant.
	 */
	private fun androidModule(
		path: String,
		moduleDeps: List<String> = emptyList(),
		externalJars: List<String> = emptyList(),
	): AndroidModule {
		val graph = AndroidModels.DependencyGraph.newBuilder()
		val variantDeps = AndroidModels.VariantDependencies.newBuilder().setName(VARIANT)

		fun addRoot(library: AndroidModels.Library) {
			val keyId = graph.keyCount
			graph.addKey(library.key)
			graph.addRoot(graph.nodeCount)
			graph.addNode(
				AndroidModels.GraphNode
					.newBuilder()
					.setKeyId(keyId)
					.build(),
			)
			variantDeps.putLibraries(library.key, library)
		}

		for (depPath in moduleDeps) {
			addRoot(
				AndroidModels.Library
					.newBuilder()
					.setKey("project$depPath")
					.setType(AndroidModels.LibraryType.Project)
					.setProjectInfo(
						AndroidModels.ProjectInfo
							.newBuilder()
							.setBuildId(":")
							.setProjectPath(depPath)
							.build(),
					).build(),
			)
		}

		for (jar in externalJars) {
			addRoot(
				AndroidModels.Library
					.newBuilder()
					.setKey("external$jar")
					.setType(AndroidModels.LibraryType.ExternalJavaLibrary)
					.setArtifactPath(jar)
					.build(),
			)
		}

		variantDeps.setMainArtifact(
			AndroidModels.ArtifactDependencies
				.newBuilder()
				.setCompileGraph(graph.build())
				.build(),
		)

		val androidProject =
			AndroidModels.AndroidProject
				.newBuilder()
				.setProjectType(AndroidModels.ProjectType.LibraryProject)
				.setVariantDependencies(variantDeps.build())
				.setClassesJarPath(generatedJarOf(path).path)
				.build()

		val dir = moduleDir(path)
		val module =
			AndroidModule(
				GradleModels.GradleProject
					.newBuilder()
					.setName(path.trimStart(':'))
					.setPath(path)
					.setProjectDirPath(dir.path)
					.setBuildDirPath(File(dir, "build").path)
					.setBuildScriptPath(File(dir, "build.gradle").path)
					.setAndroidProject(androidProject)
					.build(),
			)

		// The selected variant comes from the project manager's sync state, which a test cannot set.
		val variant =
			AndroidModels.AndroidVariant
				.newBuilder()
				.setName(VARIANT)
				.setMainArtifact(
					AndroidModels.AndroidArtifact
						.newBuilder()
						.setName(VARIANT)
						.addClassJarPaths(rJarOf(path).path)
						.build(),
				).build()
		return spyk(module).also { every { it.getSelectedVariant() } returns variant }
	}

	private fun installWorkspace(vararg modules: AndroidModule) {
		val root =
			GradleProject(
				GradleModels.GradleProject
					.newBuilder()
					.setName("root")
					.setPath(":")
					.build(),
			)
		ProjectManagerImpl.getInstance().workspace =
			Workspace(rootProject = root, subProjects = modules.toList(), syncIssues = emptyList())
	}

	private companion object {
		const val ROOT = "/tmp/module-output-delta-test"
		const val VARIANT = "debug"
	}
}
