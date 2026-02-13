package org.appdevforall.codeonthego.lsp.kotlin.server.providers

import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbol
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.Position
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.server.AnalysisScheduler
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentManager
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import org.eclipse.lsp4j.*

/**
 * Provides go-to-definition and find-references functionality.
 */
class DefinitionProvider(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val analysisScheduler: AnalysisScheduler
) {
    fun provideDefinition(uri: String, line: Int, character: Int): List<Location> {
        val state = documentManager.get(uri) ?: return emptyList()

        analysisScheduler.analyzeSync(uri)

        val syntaxTree = state.syntaxTree ?: return emptyList()
        val symbolTable = state.symbolTable

        val node = findNodeAtPosition(syntaxTree.root, line, character) ?: return emptyList()

        if (node.kind != SyntaxKind.SIMPLE_IDENTIFIER) {
            return emptyList()
        }

        val name = node.text
        val locations = mutableListOf<Location>()

        val position = Position(line, character)
        val symbols = symbolTable?.resolve(name, position) ?: emptyList()
        val localSymbol = symbols.firstOrNull()
        if (localSymbol != null) {
            val loc = symbolToLocation(localSymbol)
            if (loc != null) {
                locations.add(loc)
            }
        }

        if (locations.isEmpty()) {
            val indexedSymbol = projectIndex.findByFqName(name)
                ?: projectIndex.findByPrefix(name, limit = 10).find { it.name == name }

            if (indexedSymbol != null) {
                val loc = indexedSymbolToLocation(indexedSymbol)
                if (loc != null) {
                    locations.add(loc)
                }
            }
        }

        return locations
    }

    fun provideReferences(
        uri: String,
        line: Int,
        character: Int,
        includeDeclaration: Boolean
    ): List<Location> {
        val state = documentManager.get(uri) ?: return emptyList()

        analysisScheduler.analyzeSync(uri)

        val syntaxTree = state.syntaxTree ?: return emptyList()
        val symbolTable = state.symbolTable

        val node = findNodeAtPosition(syntaxTree.root, line, character) ?: return emptyList()

        if (node.kind != SyntaxKind.SIMPLE_IDENTIFIER) {
            return emptyList()
        }

        val name = node.text
        val references = mutableListOf<Location>()

        val position = Position(line, character)
        val symbols = symbolTable?.resolve(name, position) ?: emptyList()
        val targetSymbol = symbols.firstOrNull()

        val targetFqName = when {
            targetSymbol != null -> getFqName(targetSymbol)
            else -> {
                val indexed = projectIndex.findByPrefix(name, limit = 1).find { it.name == name }
                indexed?.fqName
            }
        }

        if (targetFqName != null) {
            if (includeDeclaration && targetSymbol != null) {
                val declLoc = symbolToLocation(targetSymbol)
                if (declLoc != null) {
                    references.add(declLoc)
                }
            }

            findReferencesInDocumentWithFqName(syntaxTree.root, name, targetFqName, symbolTable, uri, references)

            for (otherUri in documentManager.openUris) {
                if (otherUri == uri) continue

                val otherState = documentManager.get(otherUri) ?: continue
                val otherTree = otherState.syntaxTree ?: continue
                val otherSymbolTable = otherState.symbolTable

                findReferencesInDocumentWithFqName(otherTree.root, name, targetFqName, otherSymbolTable, otherUri, references)
            }

            val indexedReferences = projectIndex.findSymbolReferences(targetFqName)
            for (ref in indexedReferences) {
                val fileIndex = projectIndex.getFileIndex(ref.filePath)
                val refSymbol = fileIndex?.findByFqName(ref.referenceFqName)
                if (refSymbol != null && refSymbol.hasLocation) {
                    references.add(Location(
                        ref.filePath,
                        Range(
                            org.eclipse.lsp4j.Position(refSymbol.startLine ?: 0, refSymbol.startColumn ?: 0),
                            org.eclipse.lsp4j.Position(refSymbol.endLine ?: 0, refSymbol.endColumn ?: 0)
                        )
                    ))
                }
            }
        }

        return references.distinctBy { "${it.uri}:${it.range.start.line}:${it.range.start.character}" }
    }

    fun provideHighlights(uri: String, line: Int, character: Int): List<DocumentHighlight> {
        val state = documentManager.get(uri) ?: return emptyList()

        analysisScheduler.analyzeSync(uri)

        val syntaxTree = state.syntaxTree ?: return emptyList()
        val symbolTable = state.symbolTable

        val node = findNodeAtPosition(syntaxTree.root, line, character) ?: return emptyList()

        if (node.kind != SyntaxKind.SIMPLE_IDENTIFIER) {
            return emptyList()
        }

        val name = node.text
        val highlights = mutableListOf<DocumentHighlight>()

        val position = Position(line, character)
        val symbols = symbolTable?.resolve(name, position) ?: emptyList()
        val targetSymbol = symbols.firstOrNull()

        collectHighlights(syntaxTree.root, name, targetSymbol, highlights)

        return highlights
    }

    private fun findNodeAtPosition(root: SyntaxNode, line: Int, character: Int): SyntaxNode? {
        fun search(node: SyntaxNode): SyntaxNode? {
            if (!containsPosition(node, line, character)) return null

            for (child in node.children) {
                val found = search(child)
                if (found != null) return found
            }

            return node
        }
        return search(root)
    }

    private fun containsPosition(node: SyntaxNode, line: Int, character: Int): Boolean {
        if (line < node.startLine || line > node.endLine) return false
        if (line == node.startLine && character < node.startColumn) return false
        if (line == node.endLine && character > node.endColumn) return false
        return true
    }

    private fun symbolToLocation(symbol: Symbol): Location? {
        val location = symbol.location
        if (location.isSynthetic) return null
        return Location(
            location.filePath,
            Range(
                org.eclipse.lsp4j.Position(location.range.startLine, location.range.startColumn),
                org.eclipse.lsp4j.Position(location.range.endLine, location.range.endColumn)
            )
        )
    }

    private fun indexedSymbolToLocation(symbol: IndexedSymbol): Location? {
        val filePath = symbol.filePath ?: return null

        val startLine = symbol.startLine ?: 0
        val startColumn = symbol.startColumn ?: 0
        val endLine = symbol.endLine ?: startLine
        val endColumn = symbol.endColumn ?: startColumn

        return Location(
            filePath,
            Range(
                org.eclipse.lsp4j.Position(startLine, startColumn),
                org.eclipse.lsp4j.Position(endLine, endColumn)
            )
        )
    }

    private fun findReferencesInDocumentWithFqName(
        root: SyntaxNode,
        name: String,
        targetFqName: String,
        symbolTable: SymbolTable?,
        uri: String,
        references: MutableList<Location>
    ) {
        fun traverse(node: SyntaxNode) {
            if (node.kind == SyntaxKind.SIMPLE_IDENTIFIER && node.text == name) {
                var isMatch = false

                if (symbolTable != null) {
                    val position = Position(node.startLine, node.startColumn)
                    val resolved = symbolTable.resolve(name, position)
                    val symbol = resolved.firstOrNull()
                    if (symbol != null && symbol.qualifiedName == targetFqName) {
                        isMatch = true
                    }
                } else {
                    isMatch = true
                }

                if (isMatch) {
                    references.add(Location(
                        uri,
                        Range(
                            org.eclipse.lsp4j.Position(node.startLine, node.startColumn),
                            org.eclipse.lsp4j.Position(node.endLine, node.endColumn)
                        )
                    ))
                }
            }

            for (child in node.children) {
                traverse(child)
            }
        }
        traverse(root)
    }

    private fun collectHighlights(
        root: SyntaxNode,
        name: String,
        targetSymbol: Symbol?,
        highlights: MutableList<DocumentHighlight>
    ) {
        fun traverse(node: SyntaxNode) {
            if (node.kind == SyntaxKind.SIMPLE_IDENTIFIER) {
                val nodeText = node.text
                if (nodeText == name) {
                    val kind = determineHighlightKind(node, targetSymbol)
                    highlights.add(DocumentHighlight(
                        Range(
                            org.eclipse.lsp4j.Position(node.startLine, node.startColumn),
                            org.eclipse.lsp4j.Position(node.endLine, node.endColumn)
                        ),
                        kind
                    ))
                }
            }

            for (child in node.children) {
                traverse(child)
            }
        }
        traverse(root)
    }

    private fun determineHighlightKind(node: SyntaxNode, targetSymbol: Symbol?): DocumentHighlightKind {
        val parent = node.parent

        if (parent != null) {
            when (parent.kind) {
                SyntaxKind.FUNCTION_DECLARATION,
                SyntaxKind.CLASS_DECLARATION,
                SyntaxKind.PROPERTY_DECLARATION,
                SyntaxKind.PARAMETER -> {
                    val nameChild = parent.childByFieldName("name")
                    if (nameChild != null &&
                        nameChild.startLine == node.startLine &&
                        nameChild.startColumn == node.startColumn) {
                        return DocumentHighlightKind.Write
                    }
                }
                SyntaxKind.ASSIGNMENT,
                SyntaxKind.AUGMENTED_ASSIGNMENT -> {
                    val left = parent.childByFieldName("left")
                    if (left != null && containsSamePosition(left, node)) {
                        return DocumentHighlightKind.Write
                    }
                }
                else -> {}
            }
        }

        return DocumentHighlightKind.Read
    }

    private fun containsSamePosition(a: SyntaxNode, b: SyntaxNode): Boolean {
        return a.startLine == b.startLine && a.startColumn == b.startColumn
    }

    private fun getFqName(symbol: Symbol): String {
        return symbol.qualifiedName
    }
}
