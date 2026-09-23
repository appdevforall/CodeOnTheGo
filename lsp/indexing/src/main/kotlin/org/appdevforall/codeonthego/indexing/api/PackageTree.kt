package org.appdevforall.codeonthego.indexing.api

/**
 * The packages an index's entries make exist, as a tree of dotted names.
 *
 * An entry contributes the package [IndexDescriptor.packageOf] returns for it, and with it every
 * ancestor: an entry in `a.b.c` makes `a.b.c`, `a.b` and `a` exist. The default package (`""`) is
 * the root of the tree and is never itself an entry. A source's packages are removed with the source.
 *
 * `sourceIds` follows [IndexQuery.sourceIds]: `null` admits every source, an empty collection none.
 *
 * Both lookups block the calling thread until they return, and a persistent implementation reads
 * from disk to answer them. The caller chooses the thread; never call them on the main thread.
 */
interface PackageTree {
	/**
	 * Returns the full names of the direct subpackages of [parent] (`""` for the root packages) in
	 * any of [sourceIds], each once however many sources have it.
	 *
	 * Blocks on disk I/O in a persistent implementation, so never call it on the main thread.
	 */
	fun subpackages(
		parent: String,
		sourceIds: Collection<String>?,
	): Set<String>

	/**
	 * Returns whether any of [sourceIds] has the package [name]; `false` for the default package.
	 *
	 * Blocks on disk I/O in a persistent implementation, so never call it on the main thread.
	 */
	fun containsPackage(
		name: String,
		sourceIds: Collection<String>?,
	): Boolean
}

/** Returns the package enclosing [name], `""` for a root package. */
internal fun parentPackage(name: String): String = name.substringBeforeLast('.', missingDelimiterValue = "")

/** Returns [name] and every package enclosing it, innermost first; nothing for the default package. */
internal fun packageWithAncestors(name: String): Sequence<String> =
	generateSequence(name.ifEmpty { null }) { parentPackage(it).ifEmpty { null } }
