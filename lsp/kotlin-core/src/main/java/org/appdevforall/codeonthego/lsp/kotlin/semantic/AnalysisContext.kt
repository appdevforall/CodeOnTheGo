package org.appdevforall.codeonthego.lsp.kotlin.semantic

import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.StdlibIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxTree
import org.appdevforall.codeonthego.lsp.kotlin.parser.TextRange
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Scope
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeReference
import org.appdevforall.codeonthego.lsp.kotlin.types.ImportAwareTypeResolver
import org.appdevforall.codeonthego.lsp.kotlin.types.KotlinType
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeChecker
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeHierarchy

/**
 * Shared context for semantic analysis of a file.
 *
 * AnalysisContext holds all the state needed during semantic analysis:
 * - The syntax tree being analyzed
 * - The symbol table with all declarations
 * - Type information for expressions
 * - Diagnostics collector for errors/warnings
 * - Type checker and resolver instances
 *
 * ## Usage
 *
 * ```kotlin
 * val context = AnalysisContext(tree, symbolTable, filePath)
 * val inferrer = TypeInferrer(context)
 * val type = inferrer.inferType(expression)
 * ```
 *
 * @property tree The parsed syntax tree
 * @property symbolTable The symbol table for this file
 * @property filePath Path to the source file
 */
class AnalysisContext(
    val tree: SyntaxTree,
    val symbolTable: SymbolTable,
    val filePath: String,
    val stdlibIndex: StdlibIndex? = null,
    val projectIndex: ProjectIndex? = null,
    private val syntaxErrorRanges: List<TextRange> = emptyList()
) {
    val diagnostics: DiagnosticCollector = DiagnosticCollector()

    val typeChecker: TypeChecker = TypeChecker()

    val typeResolver: ImportAwareTypeResolver = ImportAwareTypeResolver(symbolTable, projectIndex)

    val typeHierarchy: TypeHierarchy = TypeHierarchy.DEFAULT

    fun resolveType(ref: TypeReference, scope: Scope? = null): KotlinType {
        return typeResolver.resolve(ref, scope)
    }

    private val expressionTypes = mutableMapOf<SyntaxNode, KotlinType>()

    private val resolvedReferences = mutableMapOf<SyntaxNode, Symbol>()

    private val smartCasts = mutableMapOf<SyntaxNode, SmartCastInfo>()

    private val symbolTypes = mutableMapOf<Symbol, KotlinType>()

    private val activeSmartCasts = mutableMapOf<Symbol, MutableList<SmartCastInfo>>()

    private val scopedSmartCasts = mutableListOf<ScopedSmartCast>()

    private val computingTypes = mutableSetOf<Symbol>()

    val fileScope: Scope get() = symbolTable.fileScope

    val packageName: String get() = symbolTable.packageName

    val hasErrors: Boolean get() = diagnostics.hasErrors

    fun recordType(node: SyntaxNode, type: KotlinType) {
        expressionTypes[node] = type
    }

    fun getType(node: SyntaxNode): KotlinType? {
        return expressionTypes[node]
    }

    fun recordReference(node: SyntaxNode, symbol: Symbol) {
        resolvedReferences[node] = symbol
    }

    fun getResolvedSymbol(node: SyntaxNode): Symbol? {
        return resolvedReferences[node]
    }

    fun recordSmartCast(node: SyntaxNode, info: SmartCastInfo) {
        smartCasts[node] = info
    }

    fun getSmartCast(node: SyntaxNode): SmartCastInfo? {
        return smartCasts[node]
    }

    fun pushSmartCast(symbol: Symbol, info: SmartCastInfo, scopeNode: SyntaxNode? = null) {
        activeSmartCasts.getOrPut(symbol) { mutableListOf() }.add(info)
        if (scopeNode != null) {
            scopedSmartCasts.add(ScopedSmartCast(symbol, info, scopeNode.range))
        }
    }

    fun popSmartCast(symbol: Symbol) {
        activeSmartCasts[symbol]?.removeLastOrNull()
        if (activeSmartCasts[symbol]?.isEmpty() == true) {
            activeSmartCasts.remove(symbol)
        }
    }

    fun getActiveSmartCast(symbol: Symbol): SmartCastInfo? {
        return activeSmartCasts[symbol]?.lastOrNull()
    }

    fun getSmartCastType(symbol: Symbol): KotlinType? {
        return getActiveSmartCast(symbol)?.castType
    }

    fun getSmartCastTypeAtPosition(symbol: Symbol, line: Int, column: Int): KotlinType? {
        return scopedSmartCasts
            .filter { it.symbol == symbol && it.scopeRange.containsPosition(line, column) }
            .maxByOrNull { it.scopeRange.start.line * 10000 + it.scopeRange.start.column }
            ?.info?.castType
    }

    fun recordSymbolType(symbol: Symbol, type: KotlinType) {
        symbolTypes[symbol] = type
    }

    fun getSymbolType(symbol: Symbol): KotlinType? {
        return symbolTypes[symbol]
    }

    fun startComputingType(symbol: Symbol): Boolean {
        return computingTypes.add(symbol)
    }

    fun finishComputingType(symbol: Symbol) {
        computingTypes.remove(symbol)
    }

    fun isComputingType(symbol: Symbol): Boolean {
        return symbol in computingTypes
    }

    fun reportError(code: DiagnosticCode, node: SyntaxNode, vararg args: Any) {
        if (isInsideSyntaxErrorRegion(node)) {
            return
        }
        diagnostics.error(code, node.range, *args, filePath = filePath)
    }

    private fun isInsideSyntaxErrorRegion(node: SyntaxNode): Boolean {
        if (syntaxErrorRanges.isNotEmpty()) {
            val nodeRange = node.range
            for (errorRange in syntaxErrorRanges) {
                if (errorRange.contains(nodeRange)) {
                    return true
                }
            }
        }

        var current: SyntaxNode? = node
        while (current != null) {
            if (current.isError || current.kind == SyntaxKind.ERROR) {
                return true
            }
            if (isStatementLevelNode(current.kind)) {
                return current.hasError
            }
            if (isBlockBoundary(current.kind)) {
                return false
            }
            current = current.parent
        }
        return false
    }

    private fun isStatementLevelNode(kind: SyntaxKind): Boolean {
        return kind in setOf(
            SyntaxKind.CALL_EXPRESSION,
            SyntaxKind.PROPERTY_DECLARATION,
            SyntaxKind.ASSIGNMENT,
            SyntaxKind.AUGMENTED_ASSIGNMENT,
            SyntaxKind.RETURN,
            SyntaxKind.IF_EXPRESSION,
            SyntaxKind.WHEN_EXPRESSION,
            SyntaxKind.FOR_STATEMENT,
            SyntaxKind.WHILE_STATEMENT,
            SyntaxKind.DO_WHILE_STATEMENT,
            SyntaxKind.TRY_EXPRESSION,
            SyntaxKind.NAVIGATION_EXPRESSION
        )
    }

    private fun isBlockBoundary(kind: SyntaxKind): Boolean {
        return kind in setOf(
            SyntaxKind.FUNCTION_BODY,
            SyntaxKind.CLASS_BODY,
            SyntaxKind.FUNCTION_DECLARATION,
            SyntaxKind.CLASS_DECLARATION,
            SyntaxKind.OBJECT_DECLARATION,
            SyntaxKind.SOURCE_FILE
        )
    }

    fun reportWarning(code: DiagnosticCode, node: SyntaxNode, vararg args: Any) {
        diagnostics.warning(code, node.range, *args, filePath = filePath)
    }

    fun createChildContext(): AnalysisContext {
        return AnalysisContext(tree, symbolTable, filePath, stdlibIndex, projectIndex, syntaxErrorRanges).also {
            it.diagnostics.merge(diagnostics)
        }
    }
}

/**
 * Information about a smart cast.
 */
data class SmartCastInfo(
    val originalType: KotlinType,
    val castType: KotlinType,
    val condition: SyntaxNode
)

data class ScopedSmartCast(
    val symbol: Symbol,
    val info: SmartCastInfo,
    val scopeRange: TextRange
)

private fun TextRange.containsPosition(line: Int, column: Int): Boolean {
    if (line < start.line || line > end.line) return false
    if (line == start.line && column < start.column) return false
    if (line == end.line && column > end.column) return false
    return true
}

/**
 * Result of semantic analysis for a file.
 */
data class AnalysisResult(
    val symbolTable: SymbolTable,
    val diagnostics: List<Diagnostic>,
    val analysisTimeMs: Long
) {
    val hasErrors: Boolean get() = diagnostics.any { it.isError }

    val errors: List<Diagnostic> get() = diagnostics.filter { it.isError }

    val warnings: List<Diagnostic> get() = diagnostics.filter { it.isWarning }

    val errorCount: Int get() = diagnostics.count { it.isError }

    val warningCount: Int get() = diagnostics.count { it.isWarning }
}
