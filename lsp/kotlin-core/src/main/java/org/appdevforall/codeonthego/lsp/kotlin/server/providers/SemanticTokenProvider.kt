package org.appdevforall.codeonthego.lsp.kotlin.server.providers

import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.Position
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.semantic.AnalysisContext
import org.appdevforall.codeonthego.lsp.kotlin.server.AnalysisScheduler
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentManager
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.PropertySymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeAliasSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeParameterSymbol
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend

class SemanticTokenProvider(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val analysisScheduler: AnalysisScheduler
) {
    companion object {
        val TOKEN_TYPES = listOf(
            "namespace",
            "type",
            "class",
            "enum",
            "interface",
            "struct",
            "typeParameter",
            "parameter",
            "variable",
            "property",
            "enumMember",
            "event",
            "function",
            "method",
            "macro",
            "keyword",
            "modifier",
            "comment",
            "string",
            "number",
            "regexp",
            "operator",
            "decorator"
        )

        val TOKEN_MODIFIERS = listOf(
            "declaration",
            "definition",
            "readonly",
            "static",
            "deprecated",
            "abstract",
            "async",
            "modification",
            "documentation",
            "defaultLibrary"
        )

        val LEGEND = SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS)

        private const val TYPE_NAMESPACE = 0
        private const val TYPE_TYPE = 1
        private const val TYPE_CLASS = 2
        private const val TYPE_ENUM = 3
        private const val TYPE_INTERFACE = 4
        private const val TYPE_STRUCT = 5
        private const val TYPE_TYPE_PARAMETER = 6
        private const val TYPE_PARAMETER = 7
        private const val TYPE_VARIABLE = 8
        private const val TYPE_PROPERTY = 9
        private const val TYPE_ENUM_MEMBER = 10
        private const val TYPE_EVENT = 11
        private const val TYPE_FUNCTION = 12
        private const val TYPE_METHOD = 13
        private const val TYPE_MACRO = 14
        private const val TYPE_KEYWORD = 15
        private const val TYPE_MODIFIER = 16
        private const val TYPE_COMMENT = 17
        private const val TYPE_STRING = 18
        private const val TYPE_NUMBER = 19
        private const val TYPE_REGEXP = 20
        private const val TYPE_OPERATOR = 21
        private const val TYPE_DECORATOR = 22

        private const val MOD_DECLARATION = 1 shl 0
        private const val MOD_DEFINITION = 1 shl 1
        private const val MOD_READONLY = 1 shl 2
        private const val MOD_STATIC = 1 shl 3
        private const val MOD_DEPRECATED = 1 shl 4
        private const val MOD_ABSTRACT = 1 shl 5

        private val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return",
            "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
            "var", "when", "while", "by", "catch", "constructor", "delegate",
            "dynamic", "field", "file", "finally", "get", "import", "init",
            "param", "property", "receiver", "set", "setparam", "where",
            "actual", "abstract", "annotation", "companion", "const", "crossinline",
            "data", "enum", "expect", "external", "final", "infix", "inline",
            "inner", "internal", "lateinit", "noinline", "open", "operator",
            "out", "override", "private", "protected", "public", "reified",
            "sealed", "suspend", "tailrec", "vararg"
        )
    }

    fun provideSemanticTokens(uri: String): SemanticTokens? {
        val state = documentManager.get(uri) ?: return null

        analysisScheduler.analyzeSync(uri)

        val syntaxTree = state.syntaxTree ?: return null
        val symbolTable = state.symbolTable
        val analysisContext = state.analysisContext

        val tokens = mutableListOf<SemanticToken>()
        collectTokens(syntaxTree.root, symbolTable, analysisContext, tokens)

        tokens.sortWith(compareBy({ it.line }, { it.column }))

        val data = encodeTokens(tokens)
        return SemanticTokens(data)
    }

    private fun collectTokens(
        node: SyntaxNode,
        symbolTable: SymbolTable?,
        analysisContext: AnalysisContext?,
        tokens: MutableList<SemanticToken>
    ) {
        val token = classifyNode(node, symbolTable, analysisContext)
        if (token != null) {
            tokens.add(token)
        }

        for (child in node.children) {
            collectTokens(child, symbolTable, analysisContext, tokens)
        }
    }

    private fun classifyNode(node: SyntaxNode, symbolTable: SymbolTable?, analysisContext: AnalysisContext?): SemanticToken? {
        return when (node.kind) {
            SyntaxKind.COMMENT,
            SyntaxKind.MULTILINE_COMMENT -> {
                createToken(node, TYPE_COMMENT, 0)
            }

            SyntaxKind.STRING_LITERAL,
            SyntaxKind.LINE_STRING_LITERAL,
            SyntaxKind.MULTI_LINE_STRING_LITERAL,
            SyntaxKind.CHARACTER_LITERAL -> {
                createToken(node, TYPE_STRING, 0)
            }

            SyntaxKind.INTEGER_LITERAL,
            SyntaxKind.LONG_LITERAL,
            SyntaxKind.HEX_LITERAL,
            SyntaxKind.BIN_LITERAL,
            SyntaxKind.REAL_LITERAL -> {
                createToken(node, TYPE_NUMBER, 0)
            }

            SyntaxKind.BOOLEAN_LITERAL -> {
                createToken(node, TYPE_KEYWORD, 0)
            }

            SyntaxKind.NULL_LITERAL -> {
                createToken(node, TYPE_KEYWORD, 0)
            }

            SyntaxKind.SIMPLE_IDENTIFIER -> {
                classifyIdentifier(node, symbolTable, analysisContext)
            }

            SyntaxKind.TYPE_IDENTIFIER -> {
                createToken(node, TYPE_TYPE, 0)
            }

            SyntaxKind.ANNOTATION -> {
                createToken(node, TYPE_DECORATOR, 0)
            }

            SyntaxKind.LABEL -> {
                createToken(node, TYPE_KEYWORD, 0)
            }

            else -> {
                if (isKeywordNode(node)) {
                    createToken(node, TYPE_KEYWORD, 0)
                } else if (isModifierNode(node)) {
                    createToken(node, TYPE_MODIFIER, 0)
                } else {
                    null
                }
            }
        }
    }

    private fun classifyIdentifier(node: SyntaxNode, symbolTable: SymbolTable?, analysisContext: AnalysisContext?): SemanticToken? {
        val text = node.text
        if (text.isEmpty()) return null

        if (text in KOTLIN_KEYWORDS) {
            return createToken(node, TYPE_KEYWORD, 0)
        }

        val parent = node.parent

        if (parent != null) {
            when (parent.kind) {
                SyntaxKind.FUNCTION_DECLARATION -> {
                    val nameChild = parent.childByFieldName("name")
                    if (nameChild != null && isSameNode(nameChild, node)) {
                        return createToken(node, TYPE_FUNCTION, MOD_DECLARATION or MOD_DEFINITION)
                    }
                }

                SyntaxKind.CLASS_DECLARATION -> {
                    val nameChild = parent.childByFieldName("name")
                    if (nameChild != null && isSameNode(nameChild, node)) {
                        val kind = determineClassKind(parent)
                        return createToken(node, kind, MOD_DECLARATION or MOD_DEFINITION)
                    }
                }

                SyntaxKind.OBJECT_DECLARATION -> {
                    val nameChild = parent.childByFieldName("name")
                    if (nameChild != null && isSameNode(nameChild, node)) {
                        return createToken(node, TYPE_CLASS, MOD_DECLARATION or MOD_DEFINITION)
                    }
                }

                SyntaxKind.PROPERTY_DECLARATION -> {
                    val nameChild = parent.childByFieldName("name")
                    if (nameChild != null && isSameNode(nameChild, node)) {
                        val isVal = parent.children.any { it.text == "val" }
                        val mods = MOD_DECLARATION or MOD_DEFINITION or (if (isVal) MOD_READONLY else 0)
                        return createToken(node, TYPE_PROPERTY, mods)
                    }
                }

                SyntaxKind.PARAMETER -> {
                    val nameChild = parent.childByFieldName("name")
                    if (nameChild != null && isSameNode(nameChild, node)) {
                        return createToken(node, TYPE_PARAMETER, MOD_DECLARATION)
                    }
                }

                SyntaxKind.TYPE_PARAMETER -> {
                    val nameChild = parent.childByFieldName("name")
                    if (nameChild != null && isSameNode(nameChild, node)) {
                        return createToken(node, TYPE_TYPE_PARAMETER, MOD_DECLARATION)
                    }
                }

                SyntaxKind.ENUM_ENTRY -> {
                    return createToken(node, TYPE_ENUM_MEMBER, MOD_READONLY)
                }

                SyntaxKind.IMPORT_HEADER -> {
                    return null
                }

                SyntaxKind.PACKAGE_HEADER -> {
                    return createToken(node, TYPE_NAMESPACE, 0)
                }

                SyntaxKind.CALL_EXPRESSION -> {
                    val functionNode = parent.childByFieldName("function")
                        ?: parent.children.firstOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
                    if (functionNode != null && isSameNode(functionNode, node)) {
                        return createToken(node, TYPE_FUNCTION, 0)
                    }
                }

                SyntaxKind.USER_TYPE,
                SyntaxKind.SIMPLE_USER_TYPE -> {
                    return createToken(node, TYPE_TYPE, 0)
                }

                SyntaxKind.NAVIGATION_EXPRESSION -> {
                    val suffix = parent.childByFieldName("suffix")
                        ?: parent.children.lastOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
                    if (suffix != null && isSameNode(suffix, node)) {
                        val resolvedSymbol = analysisContext?.getResolvedSymbol(node)
                        if (resolvedSymbol != null) {
                            return classifyFromSymbol(node, resolvedSymbol)
                        }
                        val grandparent = parent.parent
                        if (grandparent?.kind == SyntaxKind.CALL_EXPRESSION) {
                            return createToken(node, TYPE_METHOD, 0)
                        }
                        return createToken(node, TYPE_PROPERTY, 0)
                    }
                }

                else -> {}
            }
        }

        val resolvedSymbol = analysisContext?.getResolvedSymbol(node)
        if (resolvedSymbol != null) {
            return classifyFromSymbol(node, resolvedSymbol)
        }

        if (symbolTable != null) {
            val position = Position(node.startLine, node.startColumn)
            val resolved = symbolTable.resolve(text, position)
            val symbol = resolved.firstOrNull()

            if (symbol != null) {
                return classifyFromSymbol(node, symbol)
            }
        }

        if (text.first().isUpperCase()) {
            return createToken(node, TYPE_TYPE, 0)
        }

        return createToken(node, TYPE_VARIABLE, 0)
    }

    private fun classifyFromSymbol(node: SyntaxNode, symbol: Symbol): SemanticToken {
        return when (symbol) {
            is ClassSymbol -> {
                val kind = when {
                    symbol.isInterface -> TYPE_INTERFACE
                    symbol.isEnum -> TYPE_ENUM
                    symbol.isObject -> TYPE_CLASS
                    symbol.isAnnotation -> TYPE_DECORATOR
                    else -> TYPE_CLASS
                }
                val mods = if (symbol.modifiers.isAbstract) MOD_ABSTRACT else 0
                createToken(node, kind, mods)!!
            }
            is FunctionSymbol -> {
                val mods = buildFunctionModifiers(symbol)
                createToken(node, TYPE_FUNCTION, mods)!!
            }
            is PropertySymbol -> {
                val mods = if (!symbol.isVar) MOD_READONLY else 0
                createToken(node, TYPE_PROPERTY, mods)!!
            }
            is ParameterSymbol -> createToken(node, TYPE_PARAMETER, 0)!!
            is TypeParameterSymbol -> createToken(node, TYPE_TYPE_PARAMETER, 0)!!
            is TypeAliasSymbol -> createToken(node, TYPE_TYPE, 0)!!
            else -> createToken(node, TYPE_VARIABLE, 0)!!
        }
    }

    private fun buildFunctionModifiers(symbol: FunctionSymbol): Int {
        var mods = 0
        if (symbol.modifiers.isAbstract) mods = mods or MOD_ABSTRACT
        if (symbol.isSuspend) mods = mods or (1 shl 6)
        return mods
    }

    private fun determineClassKind(classNode: SyntaxNode): Int {
        for (child in classNode.children) {
            val text = child.text
            when {
                text == "interface" -> return TYPE_INTERFACE
                text == "enum" -> return TYPE_ENUM
                text == "annotation" -> return TYPE_DECORATOR
                text == "object" -> return TYPE_CLASS
            }
        }
        return TYPE_CLASS
    }

    private fun isKeywordNode(node: SyntaxNode): Boolean {
        val text = node.text
        return text in KOTLIN_KEYWORDS ||
            node.kind == SyntaxKind.THIS ||
            node.kind == SyntaxKind.SUPER ||
            node.kind == SyntaxKind.RETURN ||
            node.kind == SyntaxKind.THROW ||
            node.kind == SyntaxKind.IF ||
            node.kind == SyntaxKind.ELSE ||
            node.kind == SyntaxKind.WHEN ||
            node.kind == SyntaxKind.WHILE ||
            node.kind == SyntaxKind.FOR ||
            node.kind == SyntaxKind.DO ||
            node.kind == SyntaxKind.TRY ||
            node.kind == SyntaxKind.CATCH ||
            node.kind == SyntaxKind.FINALLY ||
            node.kind == SyntaxKind.BREAK ||
            node.kind == SyntaxKind.CONTINUE
    }

    private fun isModifierNode(node: SyntaxNode): Boolean {
        val parent = node.parent ?: return false
        return parent.kind == SyntaxKind.MODIFIERS ||
            parent.kind == SyntaxKind.VISIBILITY_MODIFIER ||
            parent.kind == SyntaxKind.INHERITANCE_MODIFIER ||
            parent.kind == SyntaxKind.MEMBER_MODIFIER ||
            parent.kind == SyntaxKind.FUNCTION_MODIFIER ||
            parent.kind == SyntaxKind.PROPERTY_MODIFIER ||
            parent.kind == SyntaxKind.PARAMETER_MODIFIER ||
            parent.kind == SyntaxKind.CLASS_MODIFIER
    }

    private fun isSameNode(a: SyntaxNode, b: SyntaxNode): Boolean {
        return a.startLine == b.startLine && a.startColumn == b.startColumn
    }

    private fun createToken(node: SyntaxNode, tokenType: Int, modifiers: Int): SemanticToken? {
        val text = node.text
        if (text.isEmpty()) return null

        val lines = text.split('\n')
        if (lines.size == 1) {
            return SemanticToken(
                line = node.startLine,
                column = node.startColumn,
                length = text.length,
                tokenType = tokenType,
                modifiers = modifiers
            )
        }

        return SemanticToken(
            line = node.startLine,
            column = node.startColumn,
            length = lines.first().length,
            tokenType = tokenType,
            modifiers = modifiers
        )
    }

    private fun encodeTokens(tokens: List<SemanticToken>): List<Int> {
        val data = mutableListOf<Int>()
        var prevLine = 0
        var prevColumn = 0

        for (token in tokens) {
            val deltaLine = token.line - prevLine
            val deltaColumn = if (deltaLine == 0) token.column - prevColumn else token.column

            data.add(deltaLine)
            data.add(deltaColumn)
            data.add(token.length)
            data.add(token.tokenType)
            data.add(token.modifiers)

            prevLine = token.line
            prevColumn = token.column
        }

        return data
    }

    private data class SemanticToken(
        val line: Int,
        val column: Int,
        val length: Int,
        val tokenType: Int,
        val modifiers: Int
    )
}
