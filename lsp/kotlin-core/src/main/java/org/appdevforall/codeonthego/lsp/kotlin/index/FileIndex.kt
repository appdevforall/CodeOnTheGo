package org.appdevforall.codeonthego.lsp.kotlin.index

import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.PropertySymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeAliasSymbol

/**
 * Per-file symbol index.
 *
 * FileIndex holds all symbols from a single Kotlin source file.
 * It supports efficient lookup by name and provides incremental updates
 * when a file is modified.
 *
 * ## Usage
 *
 * ```kotlin
 * val fileIndex = FileIndex.fromSymbolTable(symbolTable)
 * val classes = fileIndex.getAllClasses()
 * val functions = fileIndex.findBySimpleName("onCreate")
 * ```
 *
 * @property filePath Path to the source file
 * @property packageName The package declared in this file
 * @property lastModified Timestamp of last file modification
 */
class FileIndex(
    val filePath: String,
    val packageName: String,
    val lastModified: Long = System.currentTimeMillis()
) : MutableSymbolIndex {

    private val symbolsByFqName = mutableMapOf<String, IndexedSymbol>()
    private val symbolsByName = mutableMapOf<String, MutableList<IndexedSymbol>>()

    override val size: Int get() = symbolsByFqName.size

    override fun findByFqName(fqName: String): IndexedSymbol? {
        return symbolsByFqName[fqName]
    }

    override fun findBySimpleName(name: String): List<IndexedSymbol> {
        return symbolsByName[name]?.toList() ?: emptyList()
    }

    override fun findByPackage(packageName: String): List<IndexedSymbol> {
        if (packageName != this.packageName) {
            return emptyList()
        }
        return symbolsByFqName.values.filter { it.isTopLevel }
    }

    override fun findByPrefix(prefix: String, limit: Int): List<IndexedSymbol> {
        val results = symbolsByName.keys
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .flatMap { symbolsByName[it] ?: emptyList() }

        return if (limit > 0) results.take(limit) else results
    }

    override fun getAllClasses(): List<IndexedSymbol> {
        return symbolsByFqName.values.filter { it.kind.isClass }
    }

    override fun getAllFunctions(): List<IndexedSymbol> {
        return symbolsByFqName.values.filter { it.kind == IndexedSymbolKind.FUNCTION }
    }

    override fun getAllProperties(): List<IndexedSymbol> {
        return symbolsByFqName.values.filter { it.kind == IndexedSymbolKind.PROPERTY }
    }

    override fun add(symbol: IndexedSymbol) {
        symbolsByFqName[symbol.fqName] = symbol
        symbolsByName.getOrPut(symbol.name) { mutableListOf() }.add(symbol)
    }

    override fun remove(fqName: String): Boolean {
        val symbol = symbolsByFqName.remove(fqName) ?: return false
        symbolsByName[symbol.name]?.remove(symbol)
        if (symbolsByName[symbol.name]?.isEmpty() == true) {
            symbolsByName.remove(symbol.name)
        }
        return true
    }

    override fun clear() {
        symbolsByFqName.clear()
        symbolsByName.clear()
    }

    /**
     * Gets all top-level symbols.
     */
    fun getTopLevelSymbols(): List<IndexedSymbol> {
        return symbolsByFqName.values.filter { it.isTopLevel }
    }

    /**
     * Gets all extension symbols.
     */
    fun getExtensions(): List<IndexedSymbol> {
        return symbolsByFqName.values.filter { it.isExtension }
    }

    /**
     * Gets extensions for a specific receiver type.
     */
    fun getExtensionsFor(receiverType: String): List<IndexedSymbol> {
        return symbolsByFqName.values.filter { it.receiverType == receiverType }
    }

    /**
     * Exports to IndexData for serialization.
     */
    fun toIndexData(): IndexData {
        return IndexData.fromSymbols(symbolsByFqName.values.toList())
    }

    override fun toString(): String {
        return "FileIndex($filePath, package=$packageName, symbols=$size)"
    }

    companion object {
        /**
         * Creates a FileIndex from a SymbolTable.
         */
        fun fromSymbolTable(symbolTable: SymbolTable): FileIndex {
            val index = FileIndex(
                filePath = symbolTable.filePath,
                packageName = symbolTable.packageName
            )

            indexSymbols(symbolTable.topLevelSymbols, index, symbolTable.packageName)

            return index
        }

        private fun indexSymbols(
            symbols: List<Symbol>,
            index: FileIndex,
            packageName: String,
            containingClass: String? = null
        ) {
            for (symbol in symbols) {
                val indexed = symbolToIndexed(symbol, packageName, containingClass, index.filePath)
                if (indexed != null) {
                    index.add(indexed)
                }

                if (symbol is ClassSymbol && symbol.memberScope != null) {
                    indexSymbols(
                        symbol.members,
                        index,
                        packageName,
                        symbol.qualifiedName
                    )
                }
            }
        }

        private fun symbolToIndexed(
            symbol: Symbol,
            packageName: String,
            containingClass: String?,
            filePath: String
        ): IndexedSymbol? {
            val nameRange = symbol.location.nameRange
            val hasLocation = !symbol.location.isSynthetic

            return when (symbol) {
                is ClassSymbol -> IndexedSymbol(
                    name = symbol.name,
                    fqName = symbol.qualifiedName,
                    kind = IndexedSymbolKind.fromClassKind(symbol.kind),
                    packageName = packageName,
                    containingClass = containingClass,
                    visibility = symbol.visibility,
                    typeParameters = symbol.typeParameters.map { it.name },
                    superTypes = symbol.superTypes.map { it.render() },
                    filePath = filePath,
                    startLine = if (hasLocation) nameRange.startLine else null,
                    startColumn = if (hasLocation) nameRange.startColumn else null,
                    endLine = if (hasLocation) nameRange.endLine else null,
                    endColumn = if (hasLocation) nameRange.endColumn else null
                )
                is FunctionSymbol -> IndexedSymbol(
                    name = symbol.name,
                    fqName = symbol.qualifiedName,
                    kind = if (symbol.isConstructor) IndexedSymbolKind.CONSTRUCTOR else IndexedSymbolKind.FUNCTION,
                    packageName = packageName,
                    containingClass = containingClass,
                    visibility = symbol.visibility,
                    typeParameters = symbol.typeParameters.map { it.name },
                    parameters = symbol.parameters.map { param ->
                        IndexedParameter(
                            name = param.name,
                            type = param.type?.render() ?: "Any",
                            hasDefault = param.hasDefaultValue,
                            isVararg = param.isVararg
                        )
                    },
                    returnType = symbol.returnType?.render(),
                    receiverType = symbol.receiverType?.render(),
                    filePath = filePath,
                    startLine = if (hasLocation) nameRange.startLine else null,
                    startColumn = if (hasLocation) nameRange.startColumn else null,
                    endLine = if (hasLocation) nameRange.endLine else null,
                    endColumn = if (hasLocation) nameRange.endColumn else null
                )
                is PropertySymbol -> IndexedSymbol(
                    name = symbol.name,
                    fqName = symbol.qualifiedName,
                    kind = IndexedSymbolKind.PROPERTY,
                    packageName = packageName,
                    containingClass = containingClass,
                    visibility = symbol.visibility,
                    returnType = symbol.type?.render(),
                    receiverType = symbol.receiverType?.render(),
                    filePath = filePath,
                    startLine = if (hasLocation) nameRange.startLine else null,
                    startColumn = if (hasLocation) nameRange.startColumn else null,
                    endLine = if (hasLocation) nameRange.endLine else null,
                    endColumn = if (hasLocation) nameRange.endColumn else null
                )
                is TypeAliasSymbol -> IndexedSymbol(
                    name = symbol.name,
                    fqName = symbol.qualifiedName,
                    kind = IndexedSymbolKind.TYPE_ALIAS,
                    packageName = packageName,
                    containingClass = containingClass,
                    visibility = symbol.visibility,
                    typeParameters = symbol.typeParameters.map { it.name },
                    returnType = symbol.underlyingType?.render(),
                    filePath = filePath,
                    startLine = if (hasLocation) nameRange.startLine else null,
                    startColumn = if (hasLocation) nameRange.startColumn else null,
                    endLine = if (hasLocation) nameRange.endLine else null,
                    endColumn = if (hasLocation) nameRange.endColumn else null
                )
                else -> null
            }
        }

        /**
         * Creates a FileIndex from IndexData.
         */
        fun fromIndexData(data: IndexData, filePath: String, packageName: String): FileIndex {
            val index = FileIndex(filePath, packageName)
            data.toIndexedSymbols().forEach { index.add(it) }
            return index
        }
    }
}
