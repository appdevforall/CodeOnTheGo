package org.appdevforall.codeonthego.lsp.kotlin.index

import org.appdevforall.codeonthego.lsp.kotlin.types.ClassType
import org.appdevforall.codeonthego.lsp.kotlin.types.KotlinType

/**
 * Index of extension functions and properties by receiver type.
 *
 * ExtensionIndex provides efficient lookup of extensions applicable
 * to a given receiver type, considering:
 * - Exact type matches
 * - Nullable type variants
 * - Supertype extensions
 *
 * ## Usage
 *
 * ```kotlin
 * val extensionIndex = ExtensionIndex()
 * extensionIndex.add(listOfExtension)
 *
 * val stringExtensions = extensionIndex.findFor("kotlin.String")
 * val iterableExtensions = extensionIndex.findFor("kotlin.collections.Iterable")
 * ```
 */
class ExtensionIndex {

    private val extensionsByReceiver = mutableMapOf<String, MutableList<IndexedSymbol>>()
    private val allExtensions = mutableListOf<IndexedSymbol>()

    /**
     * Total number of extensions.
     */
    val size: Int get() = allExtensions.size

    /**
     * Whether the index is empty.
     */
    val isEmpty: Boolean get() = allExtensions.isEmpty()

    /**
     * All receiver types that have extensions.
     */
    val receiverTypes: Set<String> get() = extensionsByReceiver.keys.toSet()

    /**
     * Adds an extension to the index.
     *
     * @param extension The extension symbol (must have receiverType)
     */
    fun add(extension: IndexedSymbol) {
        if (extension.receiverType == null) return

        allExtensions.add(extension)
        val normalizedReceiver = normalizeType(extension.receiverType)
        extensionsByReceiver.getOrPut(normalizedReceiver) { mutableListOf() }.add(extension)
    }

    /**
     * Adds multiple extensions.
     */
    fun addAll(extensions: Iterable<IndexedSymbol>) {
        extensions.forEach { add(it) }
    }

    /**
     * Removes an extension from the index.
     */
    fun remove(extension: IndexedSymbol): Boolean {
        if (extension.receiverType == null) return false

        allExtensions.remove(extension)
        val normalizedReceiver = normalizeType(extension.receiverType)
        extensionsByReceiver[normalizedReceiver]?.remove(extension)
        if (extensionsByReceiver[normalizedReceiver]?.isEmpty() == true) {
            extensionsByReceiver.remove(normalizedReceiver)
        }
        return true
    }

    /**
     * Clears all extensions.
     */
    fun clear() {
        extensionsByReceiver.clear()
        allExtensions.clear()
    }

    /**
     * Finds extensions for an exact receiver type.
     *
     * @param receiverType The receiver type name
     * @return Extensions declared for that exact type
     */
    fun findExact(receiverType: String): List<IndexedSymbol> {
        val normalized = normalizeType(receiverType)
        return extensionsByReceiver[normalized]?.toList() ?: emptyList()
    }

    /**
     * Finds all extensions applicable to a receiver type.
     *
     * This includes:
     * - Extensions on the exact type
     * - Extensions on nullable variant
     * - Extensions on supertypes
     * - Extensions on Any/Any? (only if includeAnyExtensions is true)
     *
     * @param receiverType The receiver type name
     * @param supertypes Optional list of supertype names to search
     * @param includeAnyExtensions Whether to include extensions on Any/Any?
     * @return All applicable extensions
     */
    fun findFor(
        receiverType: String,
        supertypes: List<String> = emptyList(),
        includeAnyExtensions: Boolean = false
    ): List<IndexedSymbol> {
        val results = mutableListOf<IndexedSymbol>()
        val normalized = normalizeType(receiverType)

        extensionsByReceiver[normalized]?.let { results.addAll(it) }

        val nullableVariant = if (normalized.endsWith("?")) {
            normalized.dropLast(1)
        } else {
            "$normalized?"
        }
        extensionsByReceiver[nullableVariant]?.let { results.addAll(it) }

        for (supertype in supertypes) {
            val normalizedSuper = normalizeType(supertype)
            extensionsByReceiver[normalizedSuper]?.let { results.addAll(it) }
        }

        if (includeAnyExtensions) {
            extensionsByReceiver["Any"]?.let { results.addAll(it) }
            extensionsByReceiver["Any?"]?.let { results.addAll(it) }
        }

        return results.distinctBy { it.fqName }
    }

    /**
     * Finds extensions by name.
     *
     * @param name The extension name
     * @return All extensions with that name
     */
    fun findByName(name: String): List<IndexedSymbol> {
        return allExtensions.filter { it.name == name }
    }

    /**
     * Finds extensions by name prefix.
     *
     * @param prefix The name prefix
     * @param limit Maximum results (0 for unlimited)
     * @return Matching extensions
     */
    fun findByPrefix(prefix: String, limit: Int = 0): List<IndexedSymbol> {
        val results = allExtensions.filter { it.name.startsWith(prefix, ignoreCase = true) }
        return if (limit > 0) results.take(limit) else results
    }

    /**
     * Gets all extensions as a list.
     */
    fun getAll(): List<IndexedSymbol> = allExtensions.toList()

    /**
     * Gets extensions grouped by receiver type.
     */
    fun getGroupedByReceiver(): Map<String, List<IndexedSymbol>> {
        return extensionsByReceiver.mapValues { it.value.toList() }
    }

    /**
     * Merges another ExtensionIndex into this one.
     */
    fun merge(other: ExtensionIndex) {
        other.allExtensions.forEach { add(it) }
    }

    /**
     * Finds extensions applicable to a KotlinType.
     */
    fun findForType(type: KotlinType): List<IndexedSymbol> {
        val typeName = when (type) {
            is ClassType -> type.fqName
            else -> type.render()
        }

        val supertypes = when (type) {
            is ClassType -> type.symbol?.superTypes?.map { it.render() } ?: emptyList()
            else -> emptyList()
        }

        return findFor(typeName, supertypes)
    }

    private fun normalizeType(typeName: String): String {
        return typeName
            .replace(Regex("<.*>"), "")
            .trim()
    }

    override fun toString(): String {
        return "ExtensionIndex(receivers=${extensionsByReceiver.size}, extensions=$size)"
    }

    companion object {
        /**
         * Creates an ExtensionIndex from a list of symbols.
         * Only extensions are added.
         */
        fun fromSymbols(symbols: Iterable<IndexedSymbol>): ExtensionIndex {
            val index = ExtensionIndex()
            symbols.filter { it.isExtension }.forEach { index.add(it) }
            return index
        }
    }
}
