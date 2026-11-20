package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.lsp.kotlin.compiler.KotlinModuleLayout
import com.itsaky.androidide.lsp.kotlin.compiler.kotlinModuleLayout
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.models.ArtifactScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Syncs, through [ProjectManagerImpl.setup] as a real sync does, an Android module at [dir] whose
 * `src/main/kotlin` every variant in [variants] layers its own `src/<variant>/kotlin` over, with
 * [selected] configured and the Kotlin stdlib on the compile classpath, and returns the workspace's
 * Kotlin layout. Write the sources first: the layout keeps only source dirs that exist.
 */
internal fun variantLayout(
	dir: Path,
	variants: List<String>,
	selected: String,
): KotlinModuleLayout {
	val mainKotlin = dir.resolve("src/main/kotlin")
	val variantKotlin = variants.associateWith { dir.resolve("src/$it/kotlin") }
	val stdlib = checkNotNull(findKotlinStdlibJar()) { "Kotlin stdlib jar not found" }

	val module =
		GradleModels.GradleProject
			.newBuilder()
			.setName("app")
			.setPath(":app")
			.setProjectDirPath(dir.pathString)
			.setBuildDirPath(dir.resolve("build").pathString)
			.setBuildScriptPath(dir.resolve("build.gradle").pathString)
			.setAndroidProject(
				AndroidModels.AndroidProject
					.newBuilder()
					.setProjectType(AndroidModels.ProjectType.LibraryProject)
					.setMainSourceSet(
						AndroidModels.SourceSetContainer
							.newBuilder()
							.putSourceProviders(ArtifactScope.Main.key, kotlinProvider(mainKotlin)),
					).addAllVariant(variantKotlin.map { (name, kotlinDir) -> variant(name, kotlinDir) })
					.setConfiguredVariantName(selected)
					.setVariantDependencies(dependingOn(stdlib)),
			).build()

	val build =
		GradleModels.GradleBuild
			.newBuilder()
			.setRootProject(
				GradleModels.GradleProject
					.newBuilder()
					.setName("root")
					.setPath(":"),
			).addSubProject(module)
			.build()
	val manager = ProjectManagerImpl.getInstance()
	runBlocking { manager.setup(build) }
	return checkNotNull(manager.workspace).kotlinModuleLayout()
}

private fun kotlinProvider(dir: Path): AndroidModels.SourceProvider =
	AndroidModels.SourceProvider
		.newBuilder()
		.addKotlinDirs(dir.pathString)
		.build()

private fun variant(
	name: String,
	kotlinDir: Path,
): AndroidModels.AndroidVariant =
	AndroidModels.AndroidVariant
		.newBuilder()
		.setName(name)
		.setMainArtifact(AndroidModels.AndroidArtifact.newBuilder().setName("main"))
		.putArtifactSources(
			ArtifactScope.Main.key,
			AndroidModels.VariantArtifactSources
				.newBuilder()
				.addVariantSourceProviders(kotlinProvider(kotlinDir))
				.build(),
		).build()

/** Main dependencies of one root library, [jar]. */
private fun dependingOn(jar: Path): AndroidModels.VariantDependencies {
	val key = "jar:${jar.pathString}"
	val graph =
		AndroidModels.DependencyGraph
			.newBuilder()
			.addKey(key)
			.addNode(AndroidModels.GraphNode.newBuilder().setKeyId(0))
			.addRoot(0)
	return AndroidModels.VariantDependencies
		.newBuilder()
		.setName("main")
		.putArtifacts(
			ArtifactScope.Main.key,
			AndroidModels.ArtifactDependencies
				.newBuilder()
				.setCompileGraph(graph)
				.build(),
		).putLibraries(
			key,
			AndroidModels.Library
				.newBuilder()
				.setKey(key)
				.setType(AndroidModels.LibraryType.ExternalJavaLibrary)
				.setArtifactPath(jar.pathString)
				.build(),
		).build()
}
