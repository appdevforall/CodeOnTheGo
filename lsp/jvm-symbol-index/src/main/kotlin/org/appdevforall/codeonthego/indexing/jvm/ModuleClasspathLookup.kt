package org.appdevforall.codeonthego.indexing.jvm

import com.itsaky.androidide.projects.api.ModuleProject
import org.appdevforall.codeonthego.indexing.service.IndexRegistry

/**
 * Class and package lookups over one module's compile classpath, answered from the JVM symbol indexes.
 *
 * It offers what the module's classpath trie held: the top-level classes, of any visibility, of every
 * entry of the module's compile classpath, and the packages they are in. Boot classpath classes are
 * not included. Each index is queried with the module's share of it: the
 * [library][JVM_LIBRARY_SYMBOL_INDEX] and [generated][JVM_GENERATED_SYMBOL_INDEX] indexes with the
 * whole compile classpath, and the [module-output][JVM_MODULE_OUTPUT_SYMBOL_INDEX] index, which
 * holds the output JARs of every module, with the output JARs of this module and of the project
 * modules it compiles against only, so a module is never offered classes of a module it does not
 * depend on. A `null` index contributes nothing.
 *
 * Results are deduplicated by qualified name and never cached: an index being built answers with what
 * it holds so far.
 *
 * Construction does no I/O. Every lookup blocks on disk I/O, and the first one also resolves the
 * module's classpath, which stats files, so never call them on the main thread.
 *
 * Build one per request, with [of]. A lookup captures the index instances and the module's classpath
 * as they were: a re-registered index closes the one it replaces, a re-sync replaces the module
 * model, and a build can create a Java module's classes JAR after the classpath was resolved. A
 * lookup held across any of these answers from a stale classpath or a closed index, which yields
 * nothing.
 */
class ModuleClasspathLookup internal constructor(
	libraryIndex: JvmSymbolIndex?,
	generatedIndex: JvmSymbolIndex?,
	moduleOutputIndex: JvmSymbolIndex?,
	scope: () -> ClasspathScope,
) {
	/** Creates the lookup for [module] over the given indexes, any of which may be unregistered. */
	constructor(
		module: ModuleProject,
		libraryIndex: JvmSymbolIndex?,
		generatedIndex: JvmSymbolIndex?,
		moduleOutputIndex: JvmSymbolIndex?,
	) : this(libraryIndex, generatedIndex, moduleOutputIndex, { ClasspathScope.of(module) })

	/** A direct child of a package: a subpackage, or a top-level class when [isClass]. */
	data class Child(
		val name: String,
		val qualifiedName: String,
		val isClass: Boolean,
	)

	private class ScopedIndex(
		val index: JvmSymbolIndex,
		val sourceIds: Collection<String>,
	)

	private val scopedIndexes by lazy {
		val scope = scope()
		listOfNotNull(
			libraryIndex?.let { ScopedIndex(it, scope.classpath) },
			generatedIndex?.let { ScopedIndex(it, scope.classpath) },
			moduleOutputIndex?.let { ScopedIndex(it, scope.moduleOutputs) },
		)
	}

	/** Returns the qualified names of the top-level classes whose simple name is exactly [simpleName]. */
	fun qualifiedNamesOf(simpleName: String): List<String> = classesNamed(simpleName).map { it.fqName }

	/**
	 * Returns the top-level classes whose simple name is exactly [simpleName], one per qualified name.
	 *
	 * Of a class held by more than one source, the one returned is the first found.
	 */
	fun classesNamed(simpleName: String): List<JvmSymbol> =
		scopedIndexes
			.asSequence()
			.flatMap { it.index.findTopLevelClassesNamed(simpleName, it.sourceIds) }
			.distinctBy { it.fqName }
			.toList()

	/**
	 * Returns up to [limit] qualified names of top-level classes whose simple name starts with
	 * [prefix], ignoring case, exact simple-name matches first.
	 *
	 * Exact matches come first so that a short prefix, which matches far more classes than [limit],
	 * cannot crowd out the class named exactly [prefix].
	 */
	fun qualifiedNamesByPrefix(
		prefix: String,
		limit: Int,
	): List<String> {
		require(limit > 0) { "limit must be positive, was $limit" }

		val names = LinkedHashSet<String>()
		qualifiedNamesOf(prefix).take(limit).forEach(names::add)
		for (scoped in scopedIndexes) {
			addPrefixMatches(scoped, prefix, names, limit)
		}
		return names.toList()
	}

	/**
	 * Adds [scoped]'s prefix matches to [names] until it holds [limit] names or the index has no more.
	 *
	 * One fetch of the missing count is not enough: a fetched row can repeat a name already taken, and
	 * one index can hold the same class from several JARs in scope (the Kotlin stdlib split into
	 * `-jdk7`/`-jdk8`, a class duplicated across AARs). So the fetch doubles until it fills or a
	 * fetch comes back short, which means the index is exhausted.
	 */
	private fun addPrefixMatches(
		scoped: ScopedIndex,
		prefix: String,
		names: MutableSet<String>,
		limit: Int,
	) {
		var fetch = limit
		while (names.size < limit) {
			val rows = scoped.index.findTopLevelClassesByPrefix(prefix, scoped.sourceIds, fetch).toList()
			rows
				.asSequence()
				.map { it.fqName }
				.filterNot { it in names }
				.take(limit - names.size)
				.forEach(names::add)
			if (rows.size < fetch || fetch == Int.MAX_VALUE) return
			fetch = (fetch.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		}
	}

	/**
	 * Returns the direct subpackages of [packageName], then its top-level classes.
	 *
	 * [packageName] `""` is the default package: its children are the root packages and the classes
	 * declared in no package.
	 */
	fun children(packageName: String): List<Child> {
		val children = LinkedHashSet<Child>()
		for (scoped in scopedIndexes) {
			scoped.index.subpackages(packageName, scoped.sourceIds).mapTo(children) { subpackage ->
				Child(subpackage.substringAfterLast('.'), subpackage, isClass = false)
			}
		}
		for (scoped in scopedIndexes) {
			scoped.index.findTopLevelClassesInPackage(packageName, scoped.sourceIds).mapTo(children) { symbol ->
				Child(symbol.shortName, symbol.fqName, isClass = true)
			}
		}
		return children.toList()
	}

	/** Returns whether a top-level class of the classpath is in the package [name] or one of its subpackages. */
	fun isPackage(name: String): Boolean = scopedIndexes.any { it.index.containsPackage(name, it.sourceIds) }

	/** Returns whether [qualifiedName] names a top-level class of the classpath. */
	fun isClass(qualifiedName: String): Boolean {
		val packageName = qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
		val simpleName = qualifiedName.substringAfterLast('.')
		return scopedIndexes.any { it.index.containsTopLevelClass(packageName, simpleName, it.sourceIds) }
	}

	companion object {
		/** Creates the lookup for [module] over the indexes currently registered in [registry]. */
		fun of(
			module: ModuleProject,
			registry: IndexRegistry,
		) = ModuleClasspathLookup(
			module,
			registry.get(JVM_LIBRARY_SYMBOL_INDEX),
			registry.get(JVM_GENERATED_SYMBOL_INDEX),
			registry.get(JVM_MODULE_OUTPUT_SYMBOL_INDEX),
		)
	}
}

/**
 * The source ids a module's lookups are scoped to, as absolute JAR paths.
 *
 * @property classpath The module's whole compile classpath.
 * @property moduleOutputs The part of [classpath] that is the output of project modules: this module's
 * own output JAR and those of the project modules it compiles against.
 */
internal class ClasspathScope(
	val classpath: List<String>,
	val moduleOutputs: List<String>,
) {
	companion object {
		/** Resolves [module]'s scope from its project model. Stats files, so never on the main thread. */
		fun of(module: ModuleProject): ClasspathScope {
			val classpath = module.getCompileClasspaths(excludeSourceGeneratedClassPath = false)
			val external = module.getCompileClasspaths(excludeSourceGeneratedClassPath = true)
			return ClasspathScope(
				classpath = classpath.map { it.absolutePath },
				moduleOutputs = (classpath - external).map { it.absolutePath },
			)
		}
	}
}
