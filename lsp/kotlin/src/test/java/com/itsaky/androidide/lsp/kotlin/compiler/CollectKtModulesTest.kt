package com.itsaky.androidide.lsp.kotlin.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtLibraryModule
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.Workspace
import org.junit.Test
import kotlin.io.path.createFile
import kotlin.io.path.pathString

class CollectKtModulesTest : KtLspTest() {
	@Test
	fun `modules sharing android jar share one library module`() {
		val androidJar =
			lspTestRule.tempDir.root
				.toPath()
				.resolve("android.jar")
				.createFile()
		val modulePaths = listOf(":a", ":b", ":c")
		val workspace =
			Workspace(
				rootProject = GradleProject(gradleProject(":")),
				subProjects = modulePaths.map { AndroidModule(gradleProject(it, androidJar.pathString)) },
				syncIssues = emptyList(),
			)

		val sourceModules = workspace.collectKtModules(env.project, env.applicationEnv)

		val androidJarDeps =
			sourceModules.map { module ->
				module.directRegularDependencies.filterIsInstance<KtLibraryModule>().filter { it.id == androidJar.pathString }
			}
		assertThat(sourceModules).hasSize(modulePaths.size)
		// Before ADFA-6381 each source module depended on one fresh copy per Android module (3 x 3 here).
		androidJarDeps.forEach { assertThat(it).hasSize(1) }
		androidJarDeps.flatten().forEach { assertThat(it).isSameInstanceAs(androidJarDeps.first().first()) }
	}

	private fun gradleProject(
		path: String,
		bootClassPath: String? = null,
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
							.addBootClassPaths(bootClassPath),
					)
				}
			}.build()
	}
}
