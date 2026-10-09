package org.appdevforall.codeonthego.indexing.jvm

import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.JavaModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.every
import io.mockk.spyk
import org.junit.rules.TemporaryFolder
import java.io.File

/** Builds project modules laid out under [temp] and installs them as the project manager's workspace. */
internal class ModuleFixtures(
	private val temp: TemporaryFolder,
) {
	/** Creates a one-byte file at [relativePath] under [temp] and returns its absolute path. */
	fun builtFile(relativePath: String): String =
		File(temp.root, relativePath)
			.apply {
				parentFile.mkdirs()
				writeBytes(ByteArray(1))
			}.absolutePath

	private fun moduleDir(path: String) = File(temp.root, path.replace(':', '/'))

	/**
	 * Builds an [AndroidModule] whose compile graph lists [moduleDeps] as project libraries and
	 * [externalJars] as external Java libraries, with an `R.jar` on its selected variant. Its
	 * generated JAR and `R.jar` exist on disk when [built].
	 */
	fun androidModule(
		path: String,
		moduleDeps: List<String> = emptyList(),
		externalJars: List<String> = emptyList(),
		built: Boolean = true,
	): AndroidModule {
		val dir = moduleDir(path)
		val generatedJar = File(dir, "build/classes.jar")
		val rJar = File(dir, "build/R.jar")
		if (built) {
			builtFile(generatedJar.relativeTo(temp.root).path)
			builtFile(rJar.relativeTo(temp.root).path)
		}

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

		val module =
			AndroidModule(
				gradleProject(path)
					.setAndroidProject(
						AndroidModels.AndroidProject
							.newBuilder()
							.setProjectType(AndroidModels.ProjectType.LibraryProject)
							.setVariantDependencies(variantDeps.build())
							.setClassesJarPath(generatedJar.absolutePath)
							.build(),
					).build(),
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
						.addClassJarPaths(rJar.absolutePath)
						.build(),
				).build()
		return spyk(module).also { every { it.getSelectedVariant() } returns variant }
	}

	/** Builds a [JavaModule] whose `build/libs/<name>.jar` exists on disk. */
	fun javaModule(path: String): JavaModule {
		builtFile("${path.replace(':', '/')}/build/libs/${path.trimStart(':')}.jar")
		return JavaModule(
			gradleProject(path)
				.setJavaProject(JavaModels.JavaProject.getDefaultInstance())
				.build(),
		)
	}

	private fun gradleProject(path: String): GradleModels.GradleProject.Builder {
		val dir = moduleDir(path)
		return GradleModels.GradleProject
			.newBuilder()
			.setName(path.trimStart(':'))
			.setPath(path)
			.setProjectDirPath(dir.path)
			.setBuildDirPath(File(dir, "build").path)
			.setBuildScriptPath(File(dir, "build.gradle").path)
	}

	/** Installs [modules] under a root project as the project manager's workspace. */
	fun installWorkspace(vararg modules: ModuleProject): Workspace {
		val root = GradleProject(gradleProject(":").setName("root").build())
		val workspace = Workspace(rootProject = root, subProjects = modules.toList(), syncIssues = emptyList())
		ProjectManagerImpl.getInstance().workspace = workspace
		return workspace
	}

	private companion object {
		const val VARIANT = "debug"
	}
}
