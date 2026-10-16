package com.itsaky.androidide.lsp.kotlin.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtLibraryModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtSourceModule
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.pathString

class CollectKtModulesTest : KtLspTest() {
	@Test
	fun `modules sharing android jar share one library module`() {
		val androidJar = tempJar("android.jar")
		val modulePaths = listOf(":a", ":b", ":c")

		val sourceModules = collect(modulePaths.map { AndroidModule(gradleProject(it, bootClassPath = androidJar)) })

		val androidJarDeps = sourceModules.map { it.libraryDeps(androidJar) }
		assertThat(sourceModules).hasSize(modulePaths.size)
		// Before ADFA-6381 each source module depended on one fresh copy per Android module (3 x 3 here).
		androidJarDeps.forEach { assertThat(it).hasSize(1) }
		androidJarDeps.flatten().forEach { assertThat(it).isSameInstanceAs(androidJarDeps.first().first()) }
	}

	@Test
	fun `modules sharing a compile classpath jar share one library module`() {
		val libJar = tempJar("lib.jar")

		val sourceModules = collect(listOf(":a", ":b").map { JavaModule(gradleProject(it, compileJar = libJar)) })

		val libJarDeps = sourceModules.map { it.libraryDeps(libJar) }
		libJarDeps.forEach { assertThat(it).hasSize(1) }
		assertThat(libJarDeps[1].single()).isSameInstanceAs(libJarDeps[0].single())
	}

	@Test
	fun `a jar on both the boot and compile classpath is one dependency`() {
		val androidJar = tempJar("android.jar")

		// Boot classpaths from every Android module go to every source module, so :j sees
		// android.jar twice: once from :a's boot classpath, once from its own compile classpath.
		val sourceModules =
			collect(
				listOf(
					AndroidModule(gradleProject(":a", bootClassPath = androidJar)),
					JavaModule(gradleProject(":j", compileJar = androidJar)),
				),
			)

		assertThat(sourceModules.single { it.id == ":j" }.libraryDeps(androidJar)).hasSize(1)
	}

	private fun tempJar(name: String): Path =
		lspTestRule.tempDir.root
			.toPath()
			.resolve(name)
			.createFile()

	private fun collect(subProjects: List<ModuleProject>): List<KtSourceModule> =
		Workspace(
			rootProject = GradleProject(gradleProject(":")),
			subProjects = subProjects,
			syncIssues = emptyList(),
		).collectKtModules(env.project, env.applicationEnv)
			.filterIsInstance<KtSourceModule>()

	private fun KtSourceModule.libraryDeps(jar: Path) =
		directRegularDependencies.filterIsInstance<KtLibraryModule>().filter { it.id == jar.pathString }

	private fun gradleProject(
		path: String,
		bootClassPath: Path? = null,
		compileJar: Path? = null,
	): GradleModels.GradleProject {
		val dir = "/tmp/collect-kt-modules${path.replace(':', '/')}"
		return GradleModels.GradleProject
			.newBuilder()
			.setName(path.trimStart(':'))
			.setPath(path)
			.setProjectDirPath(dir)
			.setBuildDirPath("$dir/build")
			.setBuildScriptPath("$dir/build.gradle")
			.apply {
				if (bootClassPath != null) {
					setAndroidProject(
						AndroidModels.AndroidProject
							.newBuilder()
							.setProjectType(AndroidModels.ProjectType.LibraryProject)
							.addBootClassPaths(bootClassPath.pathString),
					)
				}
				if (compileJar != null) {
					setJavaProject(
						JavaModels.JavaProject.newBuilder().addDependency(
							JavaModels.JavaDependency
								.newBuilder()
								.setJarFilePath(compileJar.pathString)
								.setScope(JavaModule.SCOPE_COMPILE)
								.setExternalLibrary(JavaModels.JavaExternalLibraryDependency.getDefaultInstance()),
						),
					)
				}
			}.build()
	}
}
