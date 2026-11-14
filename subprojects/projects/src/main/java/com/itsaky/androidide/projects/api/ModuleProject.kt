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

package com.itsaky.androidide.projects.api

import android.text.TextUtils
import androidx.annotation.RestrictTo
import com.itsaky.androidide.javac.services.fs.CacheFSInfoSingleton
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.project.Common
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.models.DEFAULT_COMPILER_SETTINGS
import com.itsaky.androidide.projects.models.bootClassPaths
import com.itsaky.androidide.projects.util.BootClasspathProvider
import com.itsaky.androidide.utils.DocumentUtils
import com.itsaky.androidide.utils.SourceClassTrie
import com.itsaky.androidide.utils.SourceClassTrie.SourceNode
import com.itsaky.androidide.utils.StopWatch
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.pathString

/** A test source set kind. Each one is its own scope: neither sees the other. */
enum class TestScope {
	/** Host (JVM) tests, `src/test`. */
	UNIT,

	/** Device tests, `src/androidTest`. Android modules only. */
	ANDROID,
}

/**
 * A module project. Base class for [AndroidModule] and [JavaModule].
 *
 * @author Akash Yadav
 */
abstract class ModuleProject(
	delegate: GradleModels.GradleProject,
) : GradleProject(delegate) {
	val compilerSettings: Common.JavaCompilerSettings
		get() =
			when {
				delegate.hasJavaProject() -> delegate.javaProject.javaCompilerSettings
				delegate.hasAndroidProject() -> delegate.androidProject.javaCompilerSettings
				else -> DEFAULT_COMPILER_SETTINGS
			}

	companion object {
		private val log = LoggerFactory.getLogger(ModuleProject::class.java)

		/**
		 * Format a dependency cycle as a human-readable chain, e.g. `:a -> :b -> :a`. [recursionPath]
		 * is the current DFS path of module paths; [repeated] is the module that closes the cycle. The
		 * chain starts at the first occurrence of [repeated] on the path and ends with [repeated]
		 * again, so it shows exactly the loop the user needs to break.
		 */
		internal fun formatDependencyCycle(
			recursionPath: Collection<String>,
			repeated: String,
		): String = (recursionPath.dropWhile { it != repeated } + repeated).joinToString(" -> ")

		@JvmStatic
		val COMPLETION_MODULE_KEY = Lookup.Key<ModuleProject>()
	}

	/** The Java classes main code compiles against: this module's and its compile dependencies' sources. */
	@JvmField
	val compileJavaSourceClasses = SourceClassTrie()

	// Each scope's trie holds the main sources too, so a test compilation reads one trie, as main does.
	private val testJavaSourceClasses = TestScope.entries.associateWith { SourceClassTrie() }

	/** The Java classes [scope] tests compile against, or main's when [scope] is null. */
	fun javaSourceClasses(scope: TestScope?): SourceClassTrie =
		if (scope == null) compileJavaSourceClasses else testJavaSourceClasses.getValue(scope)

	/** The test scope whose source directories hold [file], or null for main sources and anything else. */
	fun testScopeOf(file: Path): TestScope? =
		TestScope.entries.firstOrNull { scope -> getTestSourceDirectories(scope).any { file.startsWith(it.toPath()) } }

	fun testScopeOf(file: File): TestScope? = testScopeOf(file.toPath())

	/**
	 * The tries a Java [file] belongs in, each with the source root to index it under: a main source
	 * goes in main's trie and every test scope's, a test source in each scope that holds its dir. Empty when
	 * the file is in no source directory.
	 */
	fun javaSourceClassesFor(file: File): List<Pair<SourceClassTrie, Path>> {
		findSourceRoot(file)?.let { root ->
			return listOf(compileJavaSourceClasses to root) +
				TestScope.entries
					.filter { getTestSourceDirectories(it).isNotEmpty() }
					.map { testJavaSourceClasses.getValue(it) to root }
		}
		val path = file.toPath()
		// Every matching scope, not the first: a dir such as src/sharedTest can be in both.
		return TestScope.entries.mapNotNull { scope ->
			getTestSourceDirectories(scope)
				.firstOrNull { path.startsWith(it.toPath()) }
				?.let { testJavaSourceClasses.getValue(scope) to it.toPath() }
		}
	}

	/** Every Java source trie of this module, for removing a file that may be in any of them. */
	fun allJavaSourceClasses(): List<SourceClassTrie> = listOf(compileJavaSourceClasses) + testJavaSourceClasses.values

	/**
	 * Get the source directories of this module (non-transitive i.e for this module only).
	 *
	 * @return The source directories.
	 */
	abstract fun getSourceDirectories(): Set<File>

	/**
	 * Get the source directories with compile scope. This must include source directories of
	 * transitive project dependencies and this module.
	 *
	 * @return The source directories.
	 */
	abstract fun getCompileSourceDirectories(): Set<File>

	/**
	 * Get the source directories of this module's [scope] tests (non-transitive). These are kept out
	 * of [getSourceDirectories] and [getCompileSourceDirectories], so neither main code nor dependent
	 * modules see test code.
	 *
	 * @return The test source directories, or an empty set if the module has no such tests.
	 */
	abstract fun getTestSourceDirectories(scope: TestScope): Set<File>

	/**
	 * Get the classpaths that [scope] test sources compile against: everything in
	 * [getCompileClasspaths] plus the test-only dependencies of that scope.
	 *
	 * @param excludeSourceGeneratedClassPath See [getCompileClasspaths].
	 * @return The test compile classpaths, or an empty set if the module has no such tests.
	 */
	abstract fun getTestCompileClasspaths(
		scope: TestScope,
		excludeSourceGeneratedClassPath: Boolean = false,
	): Set<File>

	/**
	 * Get the module projects [scope] tests compile against, transitively: main's, plus those only
	 * the tests depend on, such as `testImplementation(project(":lib"))`. Never this module.
	 */
	abstract fun getTestCompileModuleProjects(scope: TestScope): List<ModuleProject>

	/**
	 * The source directories [scope] tests compile against, the test counterpart of
	 * [getCompileSourceDirectories]: main's, this module's [scope] tests, and the sources of the
	 * modules only those tests depend on.
	 */
	fun getTestCompileSourceDirectories(scope: TestScope): Set<File> =
		getCompileSourceDirectories() +
			getTestSourceDirectories(scope) +
			getTestCompileModuleProjects(scope).flatMap { it.getSourceDirectories() }

	/**
	 * Get the classpaths for this module project. The returned list always included the
	 * `classes.jar`.
	 */
	abstract fun getClassPaths(): Set<File>

	/**
	 * Get the JAR files for this module. This does not include JAR files of any dependencies.
	 *
	 * @return The classpaths of this project.
	 */
	abstract fun getModuleClasspaths(): Set<File>

	/**
	 * Get the classpaths with compile scope. This must include classpaths of transitive project
	 * dependencies as well. This includes classpaths for this module as well.
	 *
	 * @param excludeSourceGeneratedClassPath Whether to exclude classpath that's generated from
	 * source files of this module or its dependencies. Defaults to `false`.
	 * @return The source directories.
	 */
	fun getCompileClasspaths(excludeSourceGeneratedClassPath: Boolean): Set<File> =
		getCompileClasspaths(excludeSourceGeneratedClassPath, HashSet())

	fun getCompileClasspaths() = getCompileClasspaths(false)

	/**
	 * Variant of [getCompileClasspaths] that guards against cyclic project-dependency graphs.
	 *
	 * @param visited module paths already expanded in this traversal. Each module contributes its
	 * classpaths at most once; a module already in [visited] returns an empty set, so a cyclic graph
	 * (e.g. `:a -> :b -> :a`) terminates instead of recursing until the stack overflows. The
	 * user-facing cycle report is emitted once by [getCompileModuleProjects]; this method only breaks
	 * the cycle.
	 */
	internal abstract fun getCompileClasspaths(
		excludeSourceGeneratedClassPath: Boolean,
		visited: MutableSet<String>,
	): Set<File>

	/**
	 * Get the intermediate build output classpaths for this module.
	 * This includes compiled .class files from the build directory that aren't packaged into JARs yet.
	 * Used for Compose Preview to reference composables from other files in the same module.
	 *
	 * @return The intermediate classpath directories/files.
	 */
	abstract fun getIntermediateClasspaths(): Set<File>

	/**
	 * Get the runtime DEX files for this module.
	 * These are pre-compiled DEX files from the build directory that can be loaded at runtime.
	 * Used for Compose Preview to load project classes like themes and shared components.
	 *
	 * @return The runtime DEX files.
	 */
	abstract fun getRuntimeDexFiles(): Set<File>

	/**
	 * Get the list of module projects with compile scope. This includes transitive module projects as
	 * well.
	 */
	fun getCompileModuleProjects(): List<ModuleProject> = getCompileModuleProjects(HashSet(), ArrayDeque())

	/**
	 * Get the list of module projects with compile scope (including transitive ones), guarding against
	 * cyclic project-dependency graphs.
	 *
	 * @param visited The set of already-expanded module paths. Each module is expanded at most once,
	 * keyed by its [path], so a diamond/shared dependency is not re-expanded and a cyclic graph
	 * terminates instead of recursing until the stack overflows. Implementations must add their own
	 * [path] before recursing into dependencies and must thread the same set through every recursive
	 * call.
	 * @param recursionPath The ordered stack of module paths currently being expanded (the DFS path
	 * from the root call down to this module). Implementations must push their own [path] before
	 * recursing and pop it afterwards. It distinguishes a *true cycle* (a module that is already an
	 * ancestor on this path) from a harmless diamond/shared dependency (a module merely seen before
	 * but not an ancestor), so only real cycles are reported via [reportDependencyCycle].
	 */
	internal abstract fun getCompileModuleProjects(
		visited: MutableSet<String>,
		recursionPath: ArrayDeque<String>,
	): List<ModuleProject>

	/**
	 * Report a detected project-dependency cycle as an actionable error. Called when a module is found
	 * to be its own ancestor in the compile-dependency graph (e.g. `:a -> :b -> :a`). The traversal
	 * breaks the cycle (returns without recursing again) to keep the IDE responsive; reporting it
	 * surfaces the misconfiguration to the user instead of silently swallowing it.
	 *
	 * @param recursionPath The current DFS path of module paths.
	 * @param repeated The module path that was found to already be on [recursionPath].
	 */
	protected fun reportDependencyCycle(
		recursionPath: ArrayDeque<String>,
		repeated: String,
	) {
		log.error(
			"Module dependency cycle detected: {}. Breaking the cycle to keep the IDE responsive; " +
				"the project's module/classpath graph may be incomplete until you remove one of these " +
				"dependencies (check the build.gradle of the modules in the cycle).",
			formatDependencyCycle(recursionPath, repeated),
		)
	}

	/**
	 * Check if the given module is a dependency of this module.
	 *
	 * @param group The group of the module.
	 * @param name The name of the module.
	 * @return `true` if the module is a dependency of this module, `false` otherwise.
	 */
	abstract fun hasExternalDependency(
		group: String,
		name: String,
	): Boolean

	/**
	 * Find the source root of the given [file].
	 *
	 * @param file The file to find the source root for.
	 * @return The source root (directory) of the given file, or `null` if not found.
	 */
	fun findSourceRoot(file: File): Path? {
		// Path.startsWith matches whole directories: src/main/javaTest is not under src/main/java.
		val path = file.toPath()
		return getCompileSourceDirectories().map(File::toPath).find { path.startsWith(it) }
	}

	/** Indexes the source files' classes and prepares the classpaths for the compiler. */
	@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
	fun indexSourcesAndClasspaths() {
		log.info("Indexing sources and classpaths for project: {}", path)
		indexSources()
		indexClasspaths()
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
	fun indexClasspaths() {
		val paths = getCompileClasspaths().filter { it.exists() }

		for (path in paths) {
			// Use 'getCanonicalFile' just to be sure that caches are stored with correct keys
			// See JavacFileManager.getContainer(Path) for more details
			CacheFSInfoSingleton.cache(CacheFSInfoSingleton.getCanonicalFile(path.toPath()))
		}

		if (this is AndroidModule) {
			BootClasspathProvider.update(bootClassPaths.map { it.path })
		}
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
	fun indexSources() {
		allJavaSourceClasses().forEach { it.clear() }

		val watch = StopWatch("Indexing sources")
		val mainSources = javaSourcesIn(getCompileSourceDirectories())
		mainSources.forEach { (file, root) -> compileJavaSourceClasses.append(file, root) }

		var count = mainSources.size
		for (scope in TestScope.entries) {
			if (getTestSourceDirectories(scope).isEmpty()) {
				continue
			}
			val trie = testJavaSourceClasses.getValue(scope)
			val testSources = javaSourcesIn(getTestCompileSourceDirectories(scope) - getCompileSourceDirectories())
			(mainSources + testSources).forEach { (file, root) -> trie.append(file, root) }
			count += testSources.size
		}

		watch.log()
		log.debug("Found {} source files.", count)
	}

	/** Every Java file under [dirs], paired with the directory it was found under. */
	private fun javaSourcesIn(dirs: Collection<File>): List<Pair<Path, Path>> =
		dirs.flatMap { dir ->
			val root = dir.toPath()
			dir
				.walk()
				.filter { file -> file.isFile && file.exists() && DocumentUtils.isJavaFile(file.toPath()) }
				.map { file -> file.toPath() to root }
				.toList()
		}

	fun getSourceFilesInDir(dir: Path): List<SourceNode> = this.compileJavaSourceClasses.getSourceFilesInDir(dir)

	/** The package of [file] as [scope]'s compilation sees it; main's when [scope] is null. */
	@JvmOverloads
	fun packageNameOrEmpty(
		file: Path?,
		scope: TestScope? = null,
	): String {
		if (file == null) {
			return ""
		}

		val sourceNode = searchSourceFileRelatively(file, scope)
		if (sourceNode != null) {
			return sourceNode.packageName
		}

		return ""
	}

	private fun searchSourceFileRelatively(
		file: Path?,
		scope: TestScope?,
	): SourceNode? {
		val roots = scope?.let(::getTestCompileSourceDirectories) ?: getCompileSourceDirectories()
		for (source in roots.map(File::toPath)) {
			val relative = source.relativize(file)
			if (relative.pathString.contains("..")) {
				// This is most probably not the one we're expecting
				continue
			}

			var name = relative.pathString.substringBeforeLast(".java")
			name = name.replace('/', '.')

			val node = javaSourceClasses(scope).findNode(name)
			if (node != null && node is SourceNode) {
				return node
			}
		}

		return null
	}

	fun suggestPackageName(file: Path): String {
		var dir = file.parent.normalize()
		while (dir != null) {
			for (sibling in getSourceFilesInDir(dir)) {
				if (DocumentUtils.isSameFile(sibling.file, file)) {
					continue
				}
				var packageName: String = packageNameOrEmpty(sibling.file)
				if (TextUtils.isEmpty(packageName.trim { it <= ' ' })) {
					continue
				}
				val relativePath = dir.relativize(file.parent)
				val relativePackage = relativePath.toString().replace(File.separatorChar, '.')
				if (relativePackage.isNotEmpty()) {
					packageName = "$packageName.$relativePackage"
				}
				return packageName
			}
			dir = dir.parent.normalize()
		}
		return ""
	}

	@JvmOverloads
	fun listClassesFromSourceDirs(
		packageName: String,
		scope: TestScope? = null,
	): List<SourceNode> =
		javaSourceClasses(scope)
			.findInPackage(packageName)
			.filterIsInstance<SourceNode>()

	open fun isFromThisModule(file: File): Boolean = isFromThisModule(file.toPath())

	open fun isFromThisModule(file: Path): Boolean {
		// TODO This can be probably improved
		return file.pathString.startsWith(projectDirPath)
	}

	override fun toString() = "${javaClass.simpleName}: ${this.path}"
}
