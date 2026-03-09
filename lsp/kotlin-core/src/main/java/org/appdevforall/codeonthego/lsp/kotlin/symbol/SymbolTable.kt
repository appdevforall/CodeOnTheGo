package org.appdevforall.codeonthego.lsp.kotlin.symbol

import org.appdevforall.codeonthego.lsp.kotlin.parser.Position
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxTree
import org.appdevforall.codeonthego.lsp.kotlin.parser.TextRange

/**
 * Per-file symbol storage and lookup.
 *
 * SymbolTable holds all symbols extracted from a single Kotlin source file.
 * It provides efficient lookup by:
 * - Name (with scope-aware resolution)
 * - Position (for go-to-definition)
 * - Kind (for document symbols)
 *
 * ## Usage
 *
 * ```kotlin
 * val parser = KotlinParser()
 * val result = parser.parse(source)
 * val table = SymbolBuilder.build(result.tree, filePath)
 *
 * // Find symbol at cursor
 * val symbol = table.symbolAt(Position(10, 5))
 *
 * // Find all functions
 * val functions = table.functions
 * ```
 *
 * @property filePath The source file path
 * @property packageName The package name declared in this file
 * @property fileScope The root scope for this file
 * @property syntaxTree The parsed syntax tree (for source text access)
 */
class SymbolTable(
    val filePath: String,
    val packageName: String,
    val fileScope: Scope,
    val syntaxTree: SyntaxTree? = null
) {
    private val symbolsByRange: MutableMap<TextRange, Symbol> = mutableMapOf()
    private val referencesByPosition: MutableMap<Position, SymbolReference> = mutableMapOf()

    /**
     * All top-level symbols in this file.
     */
    val topLevelSymbols: List<Symbol>
        get() = fileScope.allSymbols

    /**
     * All classes declared in this file.
     */
    val classes: List<ClassSymbol>
        get() = fileScope.findAll()

    /**
     * All top-level functions in this file.
     */
    val functions: List<FunctionSymbol>
        get() = fileScope.findAll()

    /**
     * All top-level properties in this file.
     */
    val properties: List<PropertySymbol>
        get() = fileScope.findAll()

    /**
     * All type aliases in this file.
     */
    val typeAliases: List<TypeAliasSymbol>
        get() = fileScope.findAll()

    /**
     * Total number of symbols in this file.
     */
    val symbolCount: Int
        get() = countSymbols(fileScope)

    /**
     * Imports declared in this file.
     */
    var imports: List<ImportInfo> = emptyList()
        internal set

    /**
     * Registers a symbol for range-based lookup.
     */
    fun registerSymbol(symbol: Symbol) {
        if (!symbol.location.isSynthetic) {
            symbolsByRange[symbol.location.range] = symbol
        }
    }

    /**
     * Registers a symbol reference for position-based lookup.
     */
    fun registerReference(position: Position, reference: SymbolReference) {
        referencesByPosition[position] = reference
    }

    /**
     * Finds the symbol declared at a position.
     *
     * @param position The cursor position
     * @return The symbol at that position, or null
     */
    fun symbolAt(position: Position): Symbol? {
        return symbolsByRange.entries
            .filter { (range, _) -> position in range }
            .minByOrNull { (range, _) -> range.length }
            ?.value
    }

    /**
     * Finds the symbol whose name is at a position.
     *
     * More precise than [symbolAt] - checks the name range specifically.
     */
    fun symbolNameAt(position: Position): Symbol? {
        return symbolsByRange.values
            .filter { symbol -> position in symbol.location.nameRange }
            .minByOrNull { symbol -> symbol.location.nameRange.length }
    }

    /**
     * Finds all symbols overlapping a range.
     */
    fun symbolsInRange(range: TextRange): List<Symbol> {
        return symbolsByRange.entries
            .filter { it.key.overlaps(range) }
            .map { it.value }
    }

    /**
     * Resolves a name at a specific position (considering scope).
     *
     * @param name The name to resolve
     * @param position The position for scope context
     * @return List of symbols matching the name
     */
    fun resolve(name: String, position: Position): List<Symbol> {
        val scope = scopeAt(position) ?: fileScope
        return scope.resolve(name)
    }

    /**
     * Finds the scope containing a position.
     */
    fun scopeAt(position: Position): Scope? {
        return findScopeAt(fileScope, position)
    }

    /**
     * Gets all symbols visible at a position.
     *
     * This includes symbols from the current scope and all parent scopes.
     */
    fun allVisibleSymbols(position: Position): List<Symbol> {
        val scope = scopeAt(position) ?: fileScope
        val symbols = scope.collectAll { true }
        return symbols
    }

    /**
     * Gets all symbols for document outline.
     *
     * Returns symbols in a hierarchical structure suitable for
     * displaying in an outline view.
     */
    fun documentSymbols(): List<DocumentSymbol> {
        return topLevelSymbols.mapNotNull { toDocumentSymbol(it) }
    }

    /**
     * Finds all references to a symbol within this file.
     */
    fun findReferences(symbol: Symbol): List<SymbolReference> {
        return referencesByPosition.values.filter { it.resolvedSymbol == symbol }
    }

    private fun findScopeAt(scope: Scope, position: Position): Scope? {
        if (position !in scope.range && scope.range != TextRange.EMPTY) {
            return null
        }

        for (child in scope.children) {
            val found = findScopeAt(child, position)
            if (found != null) return found
        }

        return scope
    }

    private fun countSymbols(scope: Scope): Int {
        var count = scope.symbolCount
        for (child in scope.children) {
            count += countSymbols(child)
        }
        return count
    }

    private fun toDocumentSymbol(symbol: Symbol): DocumentSymbol? {
        val children = when (symbol) {
            is ClassSymbol -> symbol.members.mapNotNull { toDocumentSymbol(it) }
            is FunctionSymbol -> emptyList()
            is PropertySymbol -> emptyList()
            else -> emptyList()
        }

        return DocumentSymbol(
            name = symbol.name,
            kind = symbol.toDocumentSymbolKind(),
            range = symbol.location.range,
            selectionRange = symbol.location.nameRange,
            children = children
        )
    }

    override fun toString(): String {
        return "SymbolTable($filePath, package=$packageName, symbols=$symbolCount)"
    }
}

/**
 * Document symbol for outline view.
 */
data class DocumentSymbol(
    val name: String,
    val kind: DocumentSymbolKind,
    val range: TextRange,
    val selectionRange: TextRange,
    val children: List<DocumentSymbol> = emptyList(),
    val detail: String? = null
)

/**
 * Kind of document symbol (for LSP symbolKind).
 */
enum class DocumentSymbolKind {
    FILE,
    MODULE,
    NAMESPACE,
    PACKAGE,
    CLASS,
    METHOD,
    PROPERTY,
    FIELD,
    CONSTRUCTOR,
    ENUM,
    INTERFACE,
    FUNCTION,
    VARIABLE,
    CONSTANT,
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
    KEY,
    NULL,
    ENUM_MEMBER,
    STRUCT,
    EVENT,
    OPERATOR,
    TYPE_PARAMETER
}

/**
 * Reference to a symbol at a specific location.
 */
data class SymbolReference(
    val range: TextRange,
    val name: String,
    val resolvedSymbol: Symbol?,
    val kind: ReferenceKind = ReferenceKind.READ
)

/**
 * Kind of symbol reference.
 */
enum class ReferenceKind {
    READ,
    WRITE,
    CALL,
    TYPE,
    IMPORT
}

/**
 * Import information.
 */
data class ImportInfo(
    val fqName: String,
    val alias: String?,
    val isStar: Boolean,
    val range: TextRange
) {
    val simpleName: String
        get() = alias ?: fqName.substringAfterLast('.')

    val packageName: String
        get() = if (isStar) fqName else fqName.substringBeforeLast('.', "")
}

/**
 * Extension to get DocumentSymbolKind for a symbol.
 */
fun Symbol.toDocumentSymbolKind(): DocumentSymbolKind = when (this) {
    is ClassSymbol -> when {
        isInterface -> DocumentSymbolKind.INTERFACE
        isEnum -> DocumentSymbolKind.ENUM
        isObject -> DocumentSymbolKind.OBJECT
        else -> DocumentSymbolKind.CLASS
    }
    is FunctionSymbol -> when {
        isConstructor -> DocumentSymbolKind.CONSTRUCTOR
        isOperator -> DocumentSymbolKind.OPERATOR
        else -> DocumentSymbolKind.FUNCTION
    }
    is PropertySymbol -> when {
        isConst -> DocumentSymbolKind.CONSTANT
        else -> DocumentSymbolKind.PROPERTY
    }
    is ParameterSymbol -> DocumentSymbolKind.VARIABLE
    is TypeParameterSymbol -> DocumentSymbolKind.TYPE_PARAMETER
    is TypeAliasSymbol -> DocumentSymbolKind.CLASS
    is PackageSymbol -> DocumentSymbolKind.PACKAGE
}
