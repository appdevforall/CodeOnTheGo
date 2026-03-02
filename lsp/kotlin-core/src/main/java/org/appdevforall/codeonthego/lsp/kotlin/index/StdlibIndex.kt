package org.appdevforall.codeonthego.lsp.kotlin.index

/**
 * Index of Kotlin standard library symbols.
 *
 * StdlibIndex provides fast lookup for all Kotlin stdlib types,
 * functions, properties, and extensions. The index is loaded from
 * a pre-generated JSON file (stdlib-index.json).
 *
 * ## Features
 *
 * - Fast O(1) class lookup by fully qualified name
 * - Extension function lookup by receiver type
 * - Prefix search for code completion
 * - Package-based organization
 *
 * ## Usage
 *
 * ```kotlin
 * val stdlibIndex = StdlibIndexLoader.loadFromAssets(context)
 * val stringClass = stdlibIndex.findByFqName("kotlin.String")
 * val stringExtensions = stdlibIndex.findExtensions("kotlin.String")
 * ```
 *
 * @property version The Kotlin stdlib version
 * @property kotlinVersion The Kotlin compiler version
 */
class StdlibIndex(
    val version: String,
    val kotlinVersion: String
) : SymbolIndex {

    private val symbolsByFqName = mutableMapOf<String, IndexedSymbol>()
    private val symbolsByName = mutableMapOf<String, MutableList<IndexedSymbol>>()
    private val symbolsByPackage = mutableMapOf<String, MutableList<IndexedSymbol>>()
    private val extensionsByReceiver = mutableMapOf<String, MutableList<IndexedSymbol>>()
    private val allSymbols = mutableListOf<IndexedSymbol>()

    override val size: Int get() = allSymbols.size

    val packageNames: Set<String> get() = symbolsByPackage.keys.toSet()

    val extensionReceiverTypes: Set<String> get() = extensionsByReceiver.keys.toSet()

    override fun findByFqName(fqName: String): IndexedSymbol? {
        return symbolsByFqName[fqName]
    }

    override fun findBySimpleName(name: String): List<IndexedSymbol> {
        return symbolsByName[name]?.toList() ?: emptyList()
    }

    override fun findByPackage(packageName: String): List<IndexedSymbol> {
        return symbolsByPackage[packageName]?.toList() ?: emptyList()
    }

    override fun findByPrefix(prefix: String, limit: Int): List<IndexedSymbol> {
        val results = symbolsByName.keys
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .flatMap { symbolsByName[it] ?: emptyList() }

        return if (limit > 0) results.take(limit) else results
    }

    override fun getAllClasses(): List<IndexedSymbol> {
        return allSymbols.filter { it.kind.isClass }
    }

    override fun getAllFunctions(): List<IndexedSymbol> {
        return allSymbols.filter { it.kind == IndexedSymbolKind.FUNCTION }
    }

    override fun getAllProperties(): List<IndexedSymbol> {
        return allSymbols.filter { it.kind == IndexedSymbolKind.PROPERTY }
    }

    /**
     * Gets all symbols.
     */
    fun getAllSymbols(): List<IndexedSymbol> {
        return allSymbols.toList()
    }

    /**
     * Finds extensions for a receiver type.
     *
     * @param receiverType The receiver type name (e.g., "kotlin.String")
     * @param supertypes Optional supertypes to include
     * @param includeAnyExtensions Whether to include extensions on Any/Any?
     * @return Extensions applicable to the receiver
     */
    fun findExtensions(
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
     */
    fun findExtensionsByName(name: String): List<IndexedSymbol> {
        return allSymbols.filter { it.isExtension && it.name == name }
    }

    /**
     * Finds members of a class.
     *
     * @param classFqName Fully qualified class name
     * @return Members of the class
     */
    fun findMembers(classFqName: String): List<IndexedSymbol> {
        return allSymbols.filter { it.containingClass == classFqName }
    }

    /**
     * Checks if a package exists in the stdlib.
     */
    fun hasPackage(packageName: String): Boolean {
        return symbolsByPackage.containsKey(packageName)
    }

    /**
     * Gets subpackages of a parent package.
     */
    fun getSubpackages(parentPackage: String): List<String> {
        val prefix = if (parentPackage.isEmpty()) "" else "$parentPackage."
        return symbolsByPackage.keys
            .filter { it.startsWith(prefix) && it != parentPackage }
            .map { name ->
                val rest = name.removePrefix(prefix)
                val firstDot = rest.indexOf('.')
                if (firstDot > 0) rest.substring(0, firstDot) else rest
            }
            .distinct()
            .map { if (parentPackage.isEmpty()) it else "$parentPackage.$it" }
    }

    /**
     * Gets commonly used symbols for quick completion.
     */
    fun getCommonSymbols(): List<IndexedSymbol> {
        val commonNames = setOf(
            "kotlin.String", "kotlin.Int", "kotlin.Boolean", "kotlin.Long",
            "kotlin.Double", "kotlin.Float", "kotlin.Any", "kotlin.Unit",
            "kotlin.collections.List", "kotlin.collections.Map", "kotlin.collections.Set",
            "kotlin.collections.MutableList", "kotlin.collections.MutableMap",
            "kotlin.Pair", "kotlin.Triple"
        )
        val commonFunctions = setOf(
            "println", "print", "listOf", "mapOf", "setOf",
            "mutableListOf", "mutableMapOf", "mutableSetOf",
            "arrayOf", "emptyList", "emptyMap", "emptySet",
            "to", "let", "run", "with", "apply", "also",
            "takeIf", "takeUnless", "repeat", "require", "check"
        )

        val results = mutableListOf<IndexedSymbol>()

        for (fqName in commonNames) {
            symbolsByFqName[fqName]?.let { results.add(it) }
        }

        for (name in commonFunctions) {
            symbolsByName[name]?.firstOrNull()?.let { results.add(it) }
        }

        return results
    }

    internal fun addSymbol(symbol: IndexedSymbol) {
        allSymbols.add(symbol)
        symbolsByFqName[symbol.fqName] = symbol
        symbolsByName.getOrPut(symbol.name) { mutableListOf() }.add(symbol)

        if (symbol.isTopLevel) {
            symbolsByPackage.getOrPut(symbol.packageName) { mutableListOf() }.add(symbol)
        }

        if (symbol.isExtension && symbol.receiverType != null) {
            val normalized = normalizeType(symbol.receiverType)
            extensionsByReceiver.getOrPut(normalized) { mutableListOf() }.add(symbol)
        }
    }

    internal fun addAll(symbols: Iterable<IndexedSymbol>) {
        symbols.forEach { addSymbol(it) }
    }

    private fun normalizeType(typeName: String): String {
        return typeName
            .replace(Regex("<.*>"), "")
            .trim()
    }

    override fun toString(): String {
        return "StdlibIndex(version=$version, kotlin=$kotlinVersion, symbols=$size)"
    }

    companion object {
        /**
         * Creates an empty StdlibIndex.
         */
        fun empty(): StdlibIndex {
            return StdlibIndex(version = "0.0", kotlinVersion = "unknown")
        }

        /**
         * Creates a StdlibIndex from StdlibIndexData.
         */
        fun fromData(data: StdlibIndexData): StdlibIndex {
            val index = StdlibIndex(
                version = data.version,
                kotlinVersion = data.kotlinVersion
            )

            index.addAll(data.toIndexedSymbols())

            return index
        }

        /**
         * Creates a StdlibIndex from IndexData.
         */
        fun fromIndexData(data: IndexData): StdlibIndex {
            val index = StdlibIndex(
                version = data.version,
                kotlinVersion = data.kotlinVersion
            )

            index.addAll(data.toIndexedSymbols())

            return index
        }
    }
}
