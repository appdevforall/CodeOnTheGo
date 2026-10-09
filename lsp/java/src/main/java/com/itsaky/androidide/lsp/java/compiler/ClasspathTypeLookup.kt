package com.itsaky.androidide.lsp.java.compiler

import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmVisibility
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup

/** The packages and top-level classes of one module's compile classpath, as import paths name them. */
interface ClasspathPackages {
	/** Returns whether [qualifiedName] names a top-level class of the classpath. */
	fun isClass(qualifiedName: String): Boolean

	/**
	 * Returns the direct subpackages of [packageName], then its top-level classes. [packageName] `""`
	 * is the default package, whose children are the root packages and the default-package classes.
	 */
	fun children(packageName: String): List<ModuleClasspathLookup.Child>
}

/** The top-level classes of one module's compile classpath, as the JVM symbol indexes hold them. */
internal interface ClasspathClassNames : ClasspathPackages {
	/**
	 * Returns the qualified names of top-level classes whose simple name starts with [prefix], ignoring
	 * case: the exact simple-name matches first, then up to [limit] more from each index.
	 */
	fun qualifiedNamesByPrefix(
		prefix: String,
		limit: Int,
	): List<String>

	/**
	 * Returns the top-level classes whose simple name is exactly [simpleName], each carrying its
	 * visibility and package.
	 */
	fun classesNamed(simpleName: String): List<JvmSymbol>
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
class ClasspathTypeLookup internal constructor(
	private val sourceClasses: () -> Collection<String>,
	private val classpath: () -> ClasspathClassNames?,
	private val bootClasses: () -> Collection<String>,
) {
	/**
	 * Returns the qualified names of top-level classes whose simple name starts with [partial],
	 * ignoring case: up to [limitPerSource] from each of the sources, the classpath and the boot
	 * classpath, exact simple-name matches first.
	 *
	 * Each source has its own limit so that one with many matches cannot crowd out another's: a
	 * library index holding dozens of `View*` classes must still leave room for `android.view.View`.
	 * The classpath applies the limit to each of its indexes, so it can return more than
	 * [limitPerSource] names, and all of them are kept. A name already taken from an earlier source
	 * does not count against a later source's limit. The caller ranks and trims the union. A caller
	 * that shows at most N names should pass N + 1, so a source with more matches than fit yields more
	 * names than it can show and it can tell the result is incomplete. There is no fuzzy matching.
	 */
	fun findTypeNamesMatching(
		partial: String,
		limitPerSource: Int,
	): List<String> {
		require(limitPerSource > 0) { "limitPerSource must be positive, was $limitPerSource" }

		val sources = sourceClasses()
		val boot = bootClasses()
		val (classpathNamed, classpathPrefixed) =
			classpath()
				?.qualifiedNamesByPrefix(partial, limitPerSource)
				.orEmpty()
				.partition { simpleNameOf(it) == partial }
		val names = LinkedHashSet<String>()

		fun add(candidates: Sequence<String>) = candidates.filterNot(names::contains).take(limitPerSource).forEach(names::add)

		fun Collection<String>.named() = asSequence().filter { simpleNameOf(it) == partial }

		fun Collection<String>.prefixed() = asSequence().filter { simpleNameOf(it).startsWith(partial, ignoreCase = true) }

		add(sources.named())
		names += classpathNamed
		add(boot.named())
		add(sources.prefixed())
		names += classpathPrefixed
		add(boot.prefixed())
		return names.toList()
	}

	/**
	 * Returns the qualified names of the top-level classes whose simple name is exactly [simpleName]
	 * that a file in [importingPackage] may import.
	 *
	 * Source and boot classes are returned unconditionally. A classpath (index-backed) class is
	 * excluded when it is package-private or file-private outside [importingPackage], since an import
	 * of it from there does not compile. A Kotlin file-private top-level class compiles to
	 * package-private bytecode, but the index records its declared Kotlin visibility as
	 * [JvmVisibility.PRIVATE], so it needs the same package check.
	 */
	fun findImportableQualifiedNames(
		simpleName: String,
		importingPackage: String,
	): List<String> {
		val names =
			sequence {
				yieldAll(sourceClasses().filter { simpleNameOf(it) == simpleName })
				classpath()?.classesNamed(simpleName)?.forEach { symbol ->
					val isPackageScoped =
						symbol.visibility == JvmVisibility.PACKAGE_PRIVATE || symbol.visibility == JvmVisibility.PRIVATE
					if (!isPackageScoped || symbol.packageName == importingPackage) {
						yield(symbol.fqName)
					}
				}
				yieldAll(bootClasses().filter { simpleNameOf(it) == simpleName })
			}.distinct()
		return names.sorted().toList()
	}

	/**
	 * Returns the packages and top-level classes of the module's compile classpath, or `null` without a
	 * module.
	 *
	 * Source and boot classes are not included. Like every lookup here, the result answers from the
	 * classpath and indexes as they are when it is created, so use it for one request only.
	 */
	fun classpathPackages(): ClasspathPackages? = classpath()

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
				override fun qualifiedNamesByPrefix(
					prefix: String,
					limit: Int,
				) = lookup.qualifiedNamesByPrefix(prefix, limit)

				override fun classesNamed(simpleName: String) = lookup.classesNamed(simpleName)

				override fun isClass(qualifiedName: String) = lookup.isClass(qualifiedName)

				override fun children(packageName: String) = lookup.children(packageName)
			}
		}
	}
}
