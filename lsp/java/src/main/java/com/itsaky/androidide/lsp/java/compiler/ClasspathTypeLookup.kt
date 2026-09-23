package com.itsaky.androidide.lsp.java.compiler

import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup

/** The top-level classes of one module's compile classpath, as the JVM symbol indexes hold them. */
interface ClasspathClassNames {
	/** Returns the qualified names of the top-level classes whose simple name is exactly [simpleName]. */
	fun qualifiedNamesOf(simpleName: String): List<String>

	/**
	 * Returns up to [limit] qualified names of top-level classes whose simple name starts with [prefix],
	 * ignoring case, exact simple-name matches first.
	 */
	fun qualifiedNamesByPrefix(
		prefix: String,
		limit: Int,
	): List<String>
}

/**
 * Top-level class name lookups for a Java compiler: its module's Java source classes, compile
 * classpath and boot classpath, deduplicated by qualified name.
 *
 * Construction does no I/O. Every lookup asks [classpath] for a fresh classpath lookup, which blocks
 * on the index's disk I/O, so never call a lookup on the main thread. A classpath lookup is never held
 * across requests: one held across a re-sync or an index re-registration answers from a stale
 * classpath or a closed index. While the index is being built, lookups answer with what it holds so
 * far.
 */
class ClasspathTypeLookup(
	private val sourceClasses: () -> Collection<String>,
	private val classpath: () -> ClasspathClassNames?,
	private val bootClasses: () -> Collection<String>,
) {
	/**
	 * Returns the qualified names of the top-level classes whose simple name is exactly [simpleName],
	 * sorted, or at most one of them when [onlyOne].
	 */
	fun findQualifiedNames(
		simpleName: String,
		onlyOne: Boolean,
	): List<String> {
		val names =
			sequence {
				yieldAll(sourceClasses().filter { simpleNameOf(it) == simpleName })
				classpath()?.let { yieldAll(it.qualifiedNamesOf(simpleName)) }
				yieldAll(bootClasses().filter { simpleNameOf(it) == simpleName })
			}.distinct()
		return if (onlyOne) names.take(1).toList() else names.sorted().toList()
	}

	/**
	 * Returns up to [limit] qualified names of top-level classes whose simple name starts with
	 * [partial], ignoring case.
	 *
	 * Exact simple-name matches come first, so a short [partial], which matches far more classes than
	 * [limit], cannot crowd out the class named exactly [partial]. There is no fuzzy matching.
	 */
	fun findTypeNamesMatching(
		partial: String,
		limit: Int,
	): List<String> {
		require(limit > 0) { "limit must be positive, was $limit" }

		val sources = sourceClasses()
		val boot = bootClasses()
		val classpath = classpath()
		val names = LinkedHashSet<String>()

		fun fill(candidates: () -> Iterable<String>) {
			if (names.size >= limit) return
			candidates()
				.asSequence()
				.filterNot(names::contains)
				.take(limit - names.size)
				.forEach(names::add)
		}

		fill { sources.filter { simpleNameOf(it) == partial } }
		fill { classpath?.qualifiedNamesOf(partial).orEmpty() }
		fill { boot.filter { simpleNameOf(it) == partial } }
		fill { sources.filter { simpleNameOf(it).startsWith(partial, ignoreCase = true) } }
		fill { classpath?.qualifiedNamesByPrefix(partial, limit).orEmpty() }
		fill { boot.filter { simpleNameOf(it).startsWith(partial, ignoreCase = true) } }
		return names.toList()
	}

	private fun simpleNameOf(qualifiedName: String) = qualifiedName.substringAfterLast('.')

	companion object {
		/**
		 * Creates the lookup for [module] over the indexes registered with the project manager, and the
		 * boot classes [bootClasses] returns. A `null` [module] has no source or classpath classes.
		 */
		@JvmStatic
		fun forModule(
			module: ModuleProject?,
			bootClasses: () -> Collection<String>,
		) = ClasspathTypeLookup(
			sourceClasses = {
				module
					?.compileJavaSourceClasses
					?.allSources()
					?.map { it.qualifiedName }
					.orEmpty()
			},
			classpath = { module?.let(::indexedClassNames) },
			bootClasses = bootClasses,
		)

		private fun indexedClassNames(module: ModuleProject): ClasspathClassNames {
			val lookup = ModuleClasspathLookup.of(module, ProjectManagerImpl.getInstance().indexingServiceManager.registry)
			return object : ClasspathClassNames {
				override fun qualifiedNamesOf(simpleName: String) = lookup.qualifiedNamesOf(simpleName)

				override fun qualifiedNamesByPrefix(
					prefix: String,
					limit: Int,
				) = lookup.qualifiedNamesByPrefix(prefix, limit)
			}
		}
	}
}
