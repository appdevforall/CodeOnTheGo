package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.Common
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.api.JavaModule
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Builds a minimal Gradle project model at [path], an Android library when [bootClassPath] or
 * [projectDeps] is set, a Java project with [compileJars] on its compile classpath (in order) otherwise.
 *
 * [projectDeps] are Gradle paths of the projects the Android library compiles against.
 */
internal fun gradleProject(
	path: String,
	bootClassPath: Path? = null,
	compileJars: List<Path> = emptyList(),
	jvmTarget: String? = null,
	projectDeps: List<String> = emptyList(),
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
			if (bootClassPath != null || projectDeps.isNotEmpty()) {
				setAndroidProject(
					AndroidModels.AndroidProject
						.newBuilder()
						.setProjectType(AndroidModels.ProjectType.LibraryProject)
						.apply { bootClassPath?.let { addBootClassPaths(it.pathString) } }
						.setVariantDependencies(projectDependencies(projectDeps)),
				)
			}
			if (compileJars.isNotEmpty() || jvmTarget != null) {
				setJavaProject(
					JavaModels.JavaProject
						.newBuilder()
						.apply {
							compileJars.forEach { jar ->
								addDependency(
									JavaModels.JavaDependency
										.newBuilder()
										.setJarFilePath(jar.pathString)
										.setScope(JavaModule.SCOPE_COMPILE)
										.setExternalLibrary(JavaModels.JavaExternalLibraryDependency.getDefaultInstance()),
								)
							}
							if (jvmTarget != null) {
								setKotlinCompilerSettings(Common.KotlinCompilerSettings.newBuilder().setJvmTarget(jvmTarget))
							}
						},
				)
			}
		}.build()
}

/** Encodes [projectPaths] as root `Project` libraries of the main artifact's compile graph, as the tooling layer does. */
private fun projectDependencies(projectPaths: List<String>): AndroidModels.VariantDependencies {
	val graph = AndroidModels.DependencyGraph.newBuilder()
	val variantDeps = AndroidModels.VariantDependencies.newBuilder().setName("debug")
	for (projectPath in projectPaths) {
		val key = "project$projectPath"
		graph.addRoot(graph.nodeCount)
		graph.addNode(AndroidModels.GraphNode.newBuilder().setKeyId(graph.keyCount))
		graph.addKey(key)
		variantDeps.putLibraries(
			key,
			AndroidModels.Library
				.newBuilder()
				.setKey(key)
				.setType(AndroidModels.LibraryType.Project)
				.setProjectInfo(
					AndroidModels.ProjectInfo
						.newBuilder()
						.setBuildId(":")
						.setProjectPath(projectPath),
				).build(),
		)
	}
	return variantDeps
		.setMainArtifact(AndroidModels.ArtifactDependencies.newBuilder().setCompileGraph(graph))
		.build()
}
