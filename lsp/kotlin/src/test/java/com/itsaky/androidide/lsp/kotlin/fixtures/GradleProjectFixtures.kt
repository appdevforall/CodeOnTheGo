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
 * [projectDeps] are Gradle paths of the projects the Android library compiles against. [unitTests]
 * and [androidTests], when set, give the Android library that test source set with those jars on
 * its test-only compile classpath.
 */
internal fun gradleProject(
	path: String,
	bootClassPath: Path? = null,
	compileJars: List<Path> = emptyList(),
	jvmTarget: String? = null,
	projectDeps: List<String> = emptyList(),
	unitTests: TestSources? = null,
	androidTests: TestSources? = null,
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
			if (bootClassPath != null || projectDeps.isNotEmpty() || unitTests != null || androidTests != null) {
				val variantDeps = projectDependencies(projectDeps).toBuilder()
				val sourceSet = AndroidModels.SourceSetContainer.newBuilder()
				unitTests?.let {
					sourceSet.setUnitTestSourceProvider(it.sourceProvider())
					variantDeps.setUnitTestArtifact(it.addJarsTo(variantDeps))
				}
				androidTests?.let {
					sourceSet.setAndroidTestSourceProvider(it.sourceProvider())
					variantDeps.setAndroidTestArtifact(it.addJarsTo(variantDeps))
				}
				setAndroidProject(
					AndroidModels.AndroidProject
						.newBuilder()
						.setProjectType(AndroidModels.ProjectType.LibraryProject)
						.apply { bootClassPath?.let { addBootClassPaths(it.pathString) } }
						.apply { if (unitTests != null || androidTests != null) setMainSourceSet(sourceSet) }
						.setVariantDependencies(variantDeps),
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

/** A test source set: its source [dirs], and the [jars] and [projects] only its tests compile against. */
internal data class TestSources(
	val dirs: List<Path>,
	val jars: List<Path> = emptyList(),
	val projects: List<String> = emptyList(),
)

private fun TestSources.sourceProvider(): AndroidModels.SourceProvider =
	AndroidModels.SourceProvider
		.newBuilder()
		.addAllJavaDirs(dirs.map { it.pathString })
		.build()

/** Registers [TestSources.jars] as libraries of [variantDeps] and returns a graph with one root per jar. */
private fun TestSources.addJarsTo(variantDeps: AndroidModels.VariantDependencies.Builder): AndroidModels.ArtifactDependencies {
	val graph = AndroidModels.DependencyGraph.newBuilder()
	for (jar in jars) {
		val key = "jar:${jar.pathString}"
		graph.addRoot(graph.nodeCount)
		graph.addNode(AndroidModels.GraphNode.newBuilder().setKeyId(graph.keyCount))
		graph.addKey(key)
		variantDeps.putLibraries(
			key,
			AndroidModels.Library
				.newBuilder()
				.setKey(key)
				.setType(AndroidModels.LibraryType.ExternalJavaLibrary)
				.setArtifactPath(jar.pathString)
				.build(),
		)
	}
	for (projectPath in projects) {
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
	return AndroidModels.ArtifactDependencies
		.newBuilder()
		.setCompileGraph(graph)
		.build()
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
