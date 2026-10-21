package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.bootClassPaths
import java.nio.file.Path

/**
 * The project structure Kotlin analysis is built from: everything the analysis modules are made of.
 *
 * Two equal layouts describe the same analysis modules, so an analysis session built from one serves
 * the other. Jars are identified by path; a jar rewritten in place is not a layout change. Compile
 * classpaths are sets because the project model does not keep their order.
 *
 * @property bootClassPaths Boot classpath jars of every Android module, existing ones only, in order.
 * @property libraries Every module's compile classpath, existing jars only.
 * @property modules One entry per source module, in workspace order.
 */
internal data class KotlinModuleLayout(
	val bootClassPaths: List<Path>,
	val libraries: Set<Path>,
	val modules: List<SourceModuleLayout>,
)

/**
 * One source module of a [KotlinModuleLayout].
 *
 * @property path The Gradle project path, which identifies the module.
 * @property sourceRoots The module's source directories.
 * @property classpath The compile classpath without generated sources.
 * @property moduleDependencies Gradle paths of the modules this one compiles against.
 * @property apiVersion The Kotlin API version, or null when the module has no Java or Android project.
 * @property jvmTarget The Kotlin JVM target, or null when the module has no Java or Android project.
 */
internal data class SourceModuleLayout(
	val path: String,
	val name: String,
	val sourceRoots: Set<Path>,
	val classpath: Set<Path>,
	val moduleDependencies: List<String>,
	val apiVersion: String?,
	val jvmTarget: String?,
)

/**
 * Returns the [KotlinModuleLayout] of this workspace. Reads the file system, so not on the main thread.
 *
 * Module dependencies and compile classpaths are resolved through the global
 * `IProjectManager.getInstance().workspace`, not this one, and come out empty while it is null.
 */
internal fun Workspace.kotlinModuleLayout(): KotlinModuleLayout {
	val moduleProjects =
		subProjects
			.filterIsInstance<ModuleProject>()
			.filter { it.path != rootProject.path }

	val bootClassPaths =
		moduleProjects
			.filterIsInstance<AndroidModule>()
			.flatMap { it.bootClassPaths }
			.filter { it.exists() }
			.map { it.toPath() }
			.distinct()

	val libraries =
		moduleProjects
			.flatMap { it.getCompileClasspaths() }
			.filter { it.exists() }
			.mapTo(LinkedHashSet()) { it.toPath() }

	return KotlinModuleLayout(
		bootClassPaths = bootClassPaths,
		libraries = libraries,
		modules = moduleProjects.map { it.sourceModuleLayout() },
	)
}

private fun ModuleProject.sourceModuleLayout(): SourceModuleLayout {
	val kotlinSettings =
		when {
			hasJavaProject() -> javaProject.kotlinCompilerSettings
			hasAndroidProject() -> androidProject.kotlinCompilerSettings
			else -> null
		}

	return SourceModuleLayout(
		path = path,
		name = name,
		sourceRoots = getSourceDirectories().mapTo(LinkedHashSet()) { it.toPath() },
		classpath = getCompileClasspaths(excludeSourceGeneratedClassPath = true).mapTo(LinkedHashSet()) { it.toPath() },
		moduleDependencies = getCompileModuleProjects().map { it.path },
		apiVersion = kotlinSettings?.apiVersion,
		jvmTarget = kotlinSettings?.jvmTarget,
	)
}
