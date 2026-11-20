package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.ArtifactScope
import com.itsaky.androidide.projects.models.bootClassPaths
import java.io.File
import java.nio.file.Path

/**
 * The project structure Kotlin analysis is built from: everything the analysis modules are made of.
 *
 * Two equal layouts describe the same analysis modules, so an analysis session built from one serves
 * the other. Jars are identified by path; a jar rewritten in place is not a layout change. Compile
 * classpaths are sets because the project model does not keep their order.
 *
 * @property bootClassPaths Boot classpath jars of every Android module, existing ones only, in order.
 * @property libraries Every module's compile and test compile classpaths, existing jars only.
 * @property modules One entry per source module, in workspace order, each Gradle module's main
 *   sources followed by its test sources.
 */
internal data class KotlinModuleLayout(
	val bootClassPaths: List<Path>,
	val libraries: Set<Path>,
	val modules: List<SourceModuleLayout>,
)

/**
 * One source module of a [KotlinModuleLayout].
 *
 * @property path Identifies the module: the Gradle project path for main sources, with a
 *   scope suffix for other scopes' sources (see [testModulePath]).
 * @property sourceRoots The module's source directories.
 * @property classpath The compile classpath without generated sources.
 * @property moduleDependencies Paths of the modules this one compiles against.
 * @property apiVersion The Kotlin API version, or null when the module has no Java or Android project.
 * @property jvmTarget The Kotlin JVM target, or null when the module has no Java or Android project.
 * @property friends Paths of the modules whose `internal` declarations this one may use: a test
 *   module's main module.
 */
internal data class SourceModuleLayout(
	val path: String,
	val name: String,
	val sourceRoots: Set<Path>,
	val classpath: Set<Path>,
	val moduleDependencies: List<String>,
	val apiVersion: String?,
	val jvmTarget: String?,
	val friends: List<String> = emptyList(),
)

/** The [SourceModuleLayout.path] of the [scope] module of the Gradle module at [path]. */
internal fun testModulePath(
	path: String,
	scope: ArtifactScope,
): String = "$path[${scope.moduleSuffix}]"

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
			.flatMap { module ->
				module.getCompileClasspaths() + module.nonMainScopes().flatMap { module.getCompileClasspaths(it) }
			}.filter { it.exists() }
			.mapTo(LinkedHashSet()) { it.toPath() }

	return KotlinModuleLayout(
		bootClassPaths = bootClassPaths,
		libraries = libraries,
		modules =
			moduleProjects.flatMap { module ->
				val main = module.sourceModuleLayout()
				listOf(main) + module.nonMainScopes().mapNotNull { module.testModuleLayout(main, it) }
			},
	)
}

private fun ModuleProject.nonMainScopes() = getArtifactScopes().filter { it != ArtifactScope.Main }

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
		sourceRoots = existingRoots(getSourceDirectories()),
		classpath = getCompileClasspaths(excludeSourceGeneratedClassPath = true).mapTo(LinkedHashSet()) { it.toPath() },
		moduleDependencies = getCompileModuleProjects().map { it.path },
		apiVersion = kotlinSettings?.apiVersion,
		jvmTarget = kotlinSettings?.jvmTarget,
	)
}

/**
 * The [scope] module layered over [main], or null when none of its source dirs exists. It is
 * main's friend, so it may use `internal` declarations, and compiles against main, everything main
 * does, the scopes it sees and its own scope-only modules. No two test modules depend on each other.
 */
private fun ModuleProject.testModuleLayout(
	main: SourceModuleLayout,
	scope: ArtifactScope,
): SourceModuleLayout? {
	val sourceRoots = existingRoots(getSourceDirectories(scope))
	if (sourceRoots.isEmpty()) {
		return null
	}

	val ownSeenScopes =
		nonMainScopes()
			.filter { it != scope && scope.sees(it) && hasScopeModule(it) }
			.map { testModulePath(path, it) }
	val otherModulesFixtures =
		getTestFixturesModuleProjects(scope)
			.filter { it.hasScopeModule(ArtifactScope.TestFixtures) }
			.map { testModulePath(it.path, ArtifactScope.TestFixtures) }
	// Scope-only project dependencies must be source modules: the scope's classpath leaves out their jars.
	val scopeOnlyModules = getCompileModuleProjects(scope).map { it.path }

	return main.copy(
		path = testModulePath(path, scope),
		name = "$name (${scope.displayName()})",
		sourceRoots = sourceRoots,
		classpath = getCompileClasspaths(scope, excludeSourceGeneratedClassPath = true).mapTo(LinkedHashSet()) { it.toPath() },
		moduleDependencies = (listOf(path) + ownSeenScopes + otherModulesFixtures + main.moduleDependencies + scopeOnlyModules).distinct(),
		friends = listOf(path),
	)
}

/** Whether [scope] gets a source module: [testModuleLayout] makes one only for existing dirs. */
private fun ModuleProject.hasScopeModule(scope: ArtifactScope): Boolean = existingRoots(getSourceDirectories(scope)).isNotEmpty()

/** `unitTest` -> `unit test`. */
private fun ArtifactScope.displayName(): String = moduleSuffix.replace(Regex("[A-Z]")) { " " + it.value.lowercase() }

// The analysis session drops a root that does not exist when it is built, so a dir created later
// (src/debug/java, most variant layers) must change the layout for the next sync to rebuild it.
private fun existingRoots(dirs: Set<File>): Set<Path> = dirs.filter { it.isDirectory }.mapTo(LinkedHashSet()) { it.toPath() }
