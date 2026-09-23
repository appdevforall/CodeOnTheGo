package com.itsaky.androidide.lsp.java.providers.completion

import com.itsaky.androidide.lsp.java.compiler.ClasspathPackages
import com.itsaky.androidide.lsp.java.providers.completion.ImportCompletionProvider.RequireMemberCompletionException
import com.itsaky.androidide.utils.ClassTrie
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child

/**
 * The packages and top-level classes that can continue an import path: those of a module's Java
 * [sourceClasses], its compile [classpath] and the [bootClasses], in that order, each name offered
 * once.
 *
 * It remembers the names it has offered, so build one per completion request. The classpath lookups
 * block on disk I/O, so never use one on the main thread.
 */
internal class ImportPathChildren(
	private val sourceClasses: ClassTrie,
	private val classpath: ClasspathPackages?,
	private val bootClasses: Collection<ClassTrie>,
) {
	private val offered = HashSet<String>()

	/**
	 * Returns the children of the package [packageName] not offered yet.
	 *
	 * @throws RequireMemberCompletionException when a segment of [packageName] before the last one is
	 * a class in any source, so the path continues into that class's members.
	 */
	fun ofPackage(packageName: String): Sequence<Child> {
		val sourceNode = walkToNode(sourceClasses, packageName)
		requireNoClasspathClassBefore(packageName)
		val bootNodes = bootClasses.mapNotNull { walkToNode(it, packageName) }
		return sequence {
			sourceNode?.let { yieldAll(childrenOf(it)) }
			classpath?.let { yieldAll(it.children(packageName)) }
			bootNodes.forEach { yieldAll(childrenOf(it)) }
		}.filter { offered.add(it.name) }
	}

	/**
	 * Returns the children of [qualifiedName], the default package when it is empty, not offered yet.
	 *
	 * The sequence throws [RequireMemberCompletionException] when it reaches a source in which
	 * [qualifiedName] is a class; the children of the sources before that one have been yielded by
	 * then.
	 */
	fun of(qualifiedName: String): Sequence<Child> =
		sequence {
			yieldAll(childrenOfNode(sourceClasses, qualifiedName))
			classpath?.let {
				if (qualifiedName.isNotEmpty() && it.isClass(qualifiedName)) {
					throw RequireMemberCompletionException()
				}
				yieldAll(it.children(qualifiedName))
			}
			bootClasses.forEach { yieldAll(childrenOfNode(it, qualifiedName)) }
		}.filter { offered.add(it.name) }

	private fun walkToNode(
		trie: ClassTrie,
		packageName: String,
	): ClassTrie.Node? {
		var node: ClassTrie.Node? = trie.root
		for (segment in trie.segments(packageName)) {
			if (node == null) {
				break
			}
			if (node.isClass) {
				throw RequireMemberCompletionException()
			}
			node = node.children[segment]
		}
		return node
	}

	private fun requireNoClasspathClassBefore(packageName: String) {
		val classpath = classpath ?: return
		val properPrefixes = packageName.indices.filter { packageName[it] == '.' }.map { packageName.substring(0, it) }
		if (properPrefixes.any(classpath::isClass)) {
			throw RequireMemberCompletionException()
		}
	}

	private fun childrenOfNode(
		trie: ClassTrie,
		qualifiedName: String,
	): List<Child> {
		val node = (if (qualifiedName.isEmpty()) trie.root else trie.findNode(qualifiedName)) ?: return emptyList()
		if (node.isClass) {
			throw RequireMemberCompletionException()
		}
		return childrenOf(node)
	}

	private fun childrenOf(node: ClassTrie.Node) = node.children.values.map { Child(it.name, it.qualifiedName, it.isClass) }
}
