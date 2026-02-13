package org.appdevforall.codeonthego.lsp.kotlin.server.providers

import org.appdevforall.codeonthego.lsp.kotlin.server.AnalysisScheduler
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentManager
import org.appdevforall.codeonthego.lsp.kotlin.symbol.*
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind

/**
 * Provides document outline/symbol information.
 *
 * DocumentSymbolProvider generates a hierarchical tree of symbols
 * in a document for the outline view.
 *
 * ## Symbol Hierarchy
 *
 * ```
 * Package
 * └── Class
 *     ├── Property
 *     ├── Function
 *     └── Nested Class
 *         └── ...
 * ```
 */
class DocumentSymbolProvider(
    private val documentManager: DocumentManager,
    private val analysisScheduler: AnalysisScheduler
) {
    fun provideDocumentSymbols(uri: String): List<DocumentSymbol> {
        val state = documentManager.get(uri) ?: return emptyList()

        analysisScheduler.analyzeSync(uri)

        val symbolTable = state.symbolTable ?: return emptyList()

        return buildSymbolHierarchy(symbolTable)
    }

    private fun buildSymbolHierarchy(symbolTable: SymbolTable): List<DocumentSymbol> {
        val symbols = mutableListOf<DocumentSymbol>()

        if (symbolTable.packageName.isNotEmpty()) {
            val packageSymbol = DocumentSymbol().apply {
                name = symbolTable.packageName
                kind = SymbolKind.Package
                range = Range(Position(0, 0), Position(0, 0))
                selectionRange = range
                children = emptyList()
            }
            symbols.add(packageSymbol)
        }

        for (symbol in symbolTable.topLevelSymbols) {
            val docSymbol = convertSymbol(symbol)
            if (docSymbol != null) {
                symbols.add(docSymbol)
            }
        }

        return symbols
    }

    private fun convertSymbol(symbol: Symbol): DocumentSymbol? {
        val location = symbol.location ?: return null
        val range = Range(
            Position(location.range.startLine, location.range.startColumn),
            Position(location.range.endLine, location.range.endColumn)
        )

        return when (symbol) {
            is ClassSymbol -> DocumentSymbol().apply {
                name = symbol.name
                kind = classKindToSymbolKind(symbol.kind)
                this.range = range
                selectionRange = range
                detail = buildClassDetail(symbol)
                children = symbol.members
                    .mapNotNull { convertSymbol(it) }
                    .plus(symbol.nestedClasses.mapNotNull { convertSymbol(it) })
            }
            is FunctionSymbol -> DocumentSymbol().apply {
                name = symbol.name
                kind = SymbolKind.Function
                this.range = range
                selectionRange = range
                detail = buildFunctionDetail(symbol)
                children = emptyList()
            }
            is PropertySymbol -> DocumentSymbol().apply {
                name = symbol.name
                kind = if (symbol.isConst) SymbolKind.Constant else SymbolKind.Property
                this.range = range
                selectionRange = range
                detail = symbol.type?.render()
                children = emptyList()
            }
            is TypeAliasSymbol -> DocumentSymbol().apply {
                name = symbol.name
                kind = SymbolKind.TypeParameter
                this.range = range
                selectionRange = range
                detail = "typealias"
                children = emptyList()
            }
            else -> null
        }
    }

    private fun classKindToSymbolKind(kind: ClassKind): SymbolKind {
        return when (kind) {
            ClassKind.CLASS -> SymbolKind.Class
            ClassKind.INTERFACE -> SymbolKind.Interface
            ClassKind.OBJECT -> SymbolKind.Object
            ClassKind.COMPANION_OBJECT -> SymbolKind.Object
            ClassKind.ENUM_CLASS -> SymbolKind.Enum
            ClassKind.ENUM_ENTRY -> SymbolKind.EnumMember
            ClassKind.ANNOTATION_CLASS -> SymbolKind.Class
            ClassKind.DATA_CLASS -> SymbolKind.Class
            ClassKind.VALUE_CLASS -> SymbolKind.Class
        }
    }

    private fun buildClassDetail(symbol: ClassSymbol): String {
        return buildString {
            append(symbol.kind.name.lowercase().replace('_', ' '))
            if (symbol.typeParameters.isNotEmpty()) {
                append("<")
                append(symbol.typeParameters.joinToString(", ") { it.name })
                append(">")
            }
            if (symbol.superTypes.isNotEmpty()) {
                append(" : ")
                append(symbol.superTypes.first().render())
                if (symbol.superTypes.size > 1) {
                    append(", ...")
                }
            }
        }
    }

    private fun buildFunctionDetail(symbol: FunctionSymbol): String {
        return buildString {
            append("(")
            append(symbol.parameters.joinToString(", ") { param ->
                "${param.name}: ${param.type?.render() ?: "Any"}"
            })
            append(")")
            symbol.returnType?.let { append(": ${it.render()}") }
        }
    }
}
