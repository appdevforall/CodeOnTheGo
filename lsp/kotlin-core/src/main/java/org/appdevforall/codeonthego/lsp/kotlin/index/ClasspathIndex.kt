package org.appdevforall.codeonthego.lsp.kotlin.index


class ClasspathIndex : SymbolIndex {

    private val symbolsByFqName = mutableMapOf<String, IndexedSymbol>()
    private val symbolsByName = mutableMapOf<String, MutableList<IndexedSymbol>>()
    private val symbolsByPackage = mutableMapOf<String, MutableList<IndexedSymbol>>()
    private val allSymbols = mutableListOf<IndexedSymbol>()
    private val sourceJars = mutableSetOf<String>()

    override val size: Int get() = allSymbols.size

    val packageNames: Set<String> get() = symbolsByPackage.keys.toSet()

    val jarCount: Int get() = sourceJars.size

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

    fun getAllSymbols(): List<IndexedSymbol> {
        return allSymbols.toList()
    }

    fun findMembers(classFqName: String): List<IndexedSymbol> {
        return allSymbols.filter { it.containingClass == classFqName }
    }

    fun hasPackage(packageName: String): Boolean {
        return symbolsByPackage.containsKey(packageName)
    }

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

    internal fun addSymbol(symbol: IndexedSymbol) {
        if (symbolsByFqName.containsKey(symbol.fqName)) {
            return
        }

        allSymbols.add(symbol)
        symbolsByFqName[symbol.fqName] = symbol
        symbolsByName.getOrPut(symbol.name) { mutableListOf() }.add(symbol)

        if (symbol.isTopLevel) {
            symbolsByPackage.getOrPut(symbol.packageName) { mutableListOf() }.add(symbol)
        }
    }

    internal fun addAll(symbols: Iterable<IndexedSymbol>) {
        symbols.forEach { addSymbol(it) }
    }

    internal fun addSourceJar(jarPath: String) {
        sourceJars.add(jarPath)
    }

    internal fun hasJar(jarPath: String): Boolean {
        return sourceJars.contains(jarPath)
    }

    fun clear() {
        allSymbols.clear()
        symbolsByFqName.clear()
        symbolsByName.clear()
        symbolsByPackage.clear()
        sourceJars.clear()
    }

    override fun toString(): String {
        return "ClasspathIndex(jars=$jarCount, symbols=$size)"
    }

    companion object {
        fun empty(): ClasspathIndex = ClasspathIndex()
    }
}
