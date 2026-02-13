package org.appdevforall.codeonthego.lsp.kotlin.index

/**
 * Index organized by package name.
 *
 * PackageIndex provides efficient lookup of symbols within a package
 * or across all packages with a common prefix.
 *
 * ## Usage
 *
 * ```kotlin
 * val packageIndex = PackageIndex()
 * packageIndex.add(symbol)
 *
 * val kotlinSymbols = packageIndex.getPackage("kotlin")
 * val collectionsSymbols = packageIndex.getPackage("kotlin.collections")
 * val allKotlin = packageIndex.getPackagesWithPrefix("kotlin")
 * ```
 */
class PackageIndex : MutableSymbolIndex {

    private val packageMap = mutableMapOf<String, MutableList<IndexedSymbol>>()
    private val fqNameMap = mutableMapOf<String, IndexedSymbol>()

    override val size: Int get() = fqNameMap.size

    /**
     * All package names in the index.
     */
    val packageNames: Set<String> get() = packageMap.keys.toSet()

    /**
     * Number of packages in the index.
     */
    val packageCount: Int get() = packageMap.size

    override fun findByFqName(fqName: String): IndexedSymbol? {
        return fqNameMap[fqName]
    }

    override fun findBySimpleName(name: String): List<IndexedSymbol> {
        return fqNameMap.values.filter { it.name == name }
    }

    override fun findByPackage(packageName: String): List<IndexedSymbol> {
        return packageMap[packageName]?.toList() ?: emptyList()
    }

    override fun findByPrefix(prefix: String, limit: Int): List<IndexedSymbol> {
        val results = fqNameMap.values.filter {
            it.name.startsWith(prefix, ignoreCase = true)
        }
        return if (limit > 0) results.take(limit) else results
    }

    override fun getAllClasses(): List<IndexedSymbol> {
        return fqNameMap.values.filter { it.kind.isClass }
    }

    override fun getAllFunctions(): List<IndexedSymbol> {
        return fqNameMap.values.filter { it.kind == IndexedSymbolKind.FUNCTION }
    }

    override fun getAllProperties(): List<IndexedSymbol> {
        return fqNameMap.values.filter { it.kind == IndexedSymbolKind.PROPERTY }
    }

    override fun add(symbol: IndexedSymbol) {
        fqNameMap[symbol.fqName] = symbol
        packageMap.getOrPut(symbol.packageName) { mutableListOf() }.add(symbol)
    }

    override fun remove(fqName: String): Boolean {
        val symbol = fqNameMap.remove(fqName) ?: return false
        packageMap[symbol.packageName]?.remove(symbol)
        if (packageMap[symbol.packageName]?.isEmpty() == true) {
            packageMap.remove(symbol.packageName)
        }
        return true
    }

    override fun clear() {
        fqNameMap.clear()
        packageMap.clear()
    }

    /**
     * Gets all symbols from packages starting with a prefix.
     *
     * @param prefix Package prefix (e.g., "kotlin" matches "kotlin", "kotlin.collections")
     * @return All symbols in matching packages
     */
    fun getPackagesWithPrefix(prefix: String): List<IndexedSymbol> {
        return packageMap.entries
            .filter { it.key.startsWith(prefix) }
            .flatMap { it.value }
    }

    /**
     * Gets all subpackages of a package.
     *
     * @param parentPackage The parent package name
     * @return Names of direct child packages
     */
    fun getSubpackages(parentPackage: String): List<String> {
        val prefix = if (parentPackage.isEmpty()) "" else "$parentPackage."
        return packageMap.keys
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
     * Gets root packages (packages with no parent).
     */
    fun getRootPackages(): List<String> {
        return packageMap.keys
            .map { it.substringBefore('.') }
            .distinct()
    }

    /**
     * Checks if a package exists.
     */
    fun hasPackage(packageName: String): Boolean {
        return packageMap.containsKey(packageName)
    }

    /**
     * Merges another PackageIndex into this one.
     */
    fun merge(other: PackageIndex) {
        other.fqNameMap.values.forEach { add(it) }
    }

    override fun toString(): String {
        return "PackageIndex(packages=$packageCount, symbols=$size)"
    }
}
