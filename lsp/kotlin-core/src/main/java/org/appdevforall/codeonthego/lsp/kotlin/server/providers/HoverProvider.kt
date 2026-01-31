package org.appdevforall.codeonthego.lsp.kotlin.server.providers

import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbol
import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbolKind
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.Position
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.server.AnalysisScheduler
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentManager
import org.appdevforall.codeonthego.lsp.kotlin.symbol.*
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Provides hover information for symbols.
 */
class HoverProvider(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val analysisScheduler: AnalysisScheduler
) {
    fun provideHover(uri: String, line: Int, character: Int): Hover? {
        val state = documentManager.get(uri) ?: return null

        analysisScheduler.analyzeSync(uri)

        val syntaxTree = state.syntaxTree ?: return null
        val symbolTable = state.symbolTable

        val node = findNodeAtPosition(syntaxTree.root, line, character) ?: return null

        val hoverContent = when {
            node.kind == SyntaxKind.SIMPLE_IDENTIFIER -> {
                val name = node.text
                getIdentifierHover(name, symbolTable, line, character)
            }
            node.kind.isKeyword -> {
                getKeywordHover(node.kind)
            }
            node.kind.isLiteral -> {
                getLiteralHover(node)
            }
            else -> null
        }

        return hoverContent?.let { content ->
            Hover().apply {
                contents = Either.forRight(MarkupContent().apply {
                    kind = MarkupKind.MARKDOWN
                    value = content
                })
                range = Range(
                    org.eclipse.lsp4j.Position(node.startLine, node.startColumn),
                    org.eclipse.lsp4j.Position(node.endLine, node.endColumn)
                )
            }
        }
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

    private fun getIdentifierHover(
        name: String,
        symbolTable: SymbolTable?,
        line: Int,
        character: Int
    ): String? {
        if (symbolTable != null) {
            val position = Position(line, character)
            val symbols = symbolTable.resolve(name, position)
            val localSymbol = symbols.firstOrNull()
            if (localSymbol != null) {
                return formatSymbolHover(localSymbol)
            }
        }

        val indexedSymbol = projectIndex.findByFqName(name)
            ?: projectIndex.findByPrefix(name, limit = 1).firstOrNull { it.name == name }

        return indexedSymbol?.let { formatIndexedSymbolHover(it) }
    }

    private fun formatSymbolHover(symbol: Symbol): String {
        return buildString {
            append("```kotlin\n")
            append(formatSymbolSignature(symbol))
            append("\n```")

            when (symbol) {
                is FunctionSymbol -> {
                    if (symbol.parameters.isNotEmpty()) {
                        append("\n\n**Parameters:**\n")
                        symbol.parameters.forEach { param ->
                            append("- `${param.name}`: ${param.type?.render() ?: "Any"}\n")
                        }
                    }
                    symbol.returnType?.let { rt ->
                        append("\n**Returns:** `${rt.render()}`")
                    }
                }
                is PropertySymbol -> {
                    symbol.type?.let { type ->
                        append("\n\n**Type:** `${type.render()}`")
                    }
                    if (symbol.isVar) {
                        append("\n\n*Mutable property (var)*")
                    }
                }
                is ClassSymbol -> {
                    if (symbol.superTypes.isNotEmpty()) {
                        append("\n\n**Inherits:** ")
                        append(symbol.superTypes.joinToString(", ") { "`${it.render()}`" })
                    }
                }
                else -> {}
            }
        }
    }

    private fun formatSymbolSignature(symbol: Symbol): String {
        return when (symbol) {
            is FunctionSymbol -> buildString {
                if (symbol.modifiers.isSuspend) append("suspend ")
                if (symbol.modifiers.isInline) append("inline ")
                append("fun ")
                if (symbol.typeParameters.isNotEmpty()) {
                    append("<")
                    append(symbol.typeParameters.joinToString(", ") { it.name })
                    append("> ")
                }
                symbol.receiverType?.let { append("${it.render()}.") }
                append(symbol.name)
                append("(")
                append(symbol.parameters.joinToString(", ") { param ->
                    buildString {
                        if (param.isVararg) append("vararg ")
                        append("${param.name}: ${param.type?.render() ?: "Any"}")
                        if (param.hasDefaultValue) append(" = ...")
                    }
                })
                append(")")
                symbol.returnType?.let { append(": ${it.render()}") }
            }
            is PropertySymbol -> buildString {
                if (symbol.isVar) append("var ") else append("val ")
                append(symbol.name)
                symbol.type?.let { append(": ${it.render()}") }
            }
            is ParameterSymbol -> buildString {
                if (symbol.isVararg) append("vararg ")
                append(symbol.name)
                symbol.type?.let { append(": ${it.render()}") }
            }
            is ClassSymbol -> buildString {
                when (symbol.kind) {
                    ClassKind.CLASS -> {
                        if (symbol.modifiers.isSealed) append("sealed ")
                        append("class ")
                    }
                    ClassKind.INTERFACE -> append("interface ")
                    ClassKind.OBJECT -> append("object ")
                    ClassKind.COMPANION_OBJECT -> append("companion object ")
                    ClassKind.ENUM_CLASS -> append("enum class ")
                    ClassKind.ENUM_ENTRY -> append("")
                    ClassKind.ANNOTATION_CLASS -> append("annotation class ")
                    ClassKind.DATA_CLASS -> append("data class ")
                    ClassKind.VALUE_CLASS -> append("value class ")
                }
                append(symbol.name)
                if (symbol.typeParameters.isNotEmpty()) {
                    append("<")
                    append(symbol.typeParameters.joinToString(", ") { it.name })
                    append(">")
                }
            }
            is TypeParameterSymbol -> buildString {
                append(symbol.name)
                symbol.effectiveBound?.let { append(" : ${it.render()}") }
            }
            else -> symbol.name
        }
    }

    private fun formatIndexedSymbolHover(symbol: IndexedSymbol): String {
        return buildString {
            append("```kotlin\n")
            append(symbol.toDisplayString())
            append("\n```")

            append("\n\n**Package:** `${symbol.packageName}`")

            symbol.containingClass?.let { cls ->
                append("\n\n**Containing class:** `$cls`")
            }

            if (symbol.deprecated) {
                append("\n\n⚠️ **Deprecated**")
                symbol.deprecationMessage?.let { msg ->
                    append(": $msg")
                }
            }

            if (symbol.isExtension) {
                append("\n\n*Extension ${if (symbol.kind == IndexedSymbolKind.FUNCTION) "function" else "property"}*")
            }
        }
    }

    private fun getKeywordHover(kind: SyntaxKind): String? {
        val description = KEYWORD_DESCRIPTIONS[kind] ?: return null
        return buildString {
            append("**`${kind.name.lowercase()}`** — ")
            append(description)
        }
    }

    private fun getLiteralHover(node: SyntaxNode): String? {
        val text = node.text
        return when (node.kind) {
            SyntaxKind.INTEGER_LITERAL -> {
                val value = parseIntegerLiteral(text)
                "```kotlin\n$text\n```\n\n**Type:** `Int` or `Long`\n\n**Value:** $value"
            }
            SyntaxKind.REAL_LITERAL -> {
                "```kotlin\n$text\n```\n\n**Type:** `Double` or `Float`"
            }
            SyntaxKind.STRING_LITERAL, SyntaxKind.MULTI_LINE_STRING_LITERAL -> {
                "```kotlin\n$text\n```\n\n**Type:** `String`"
            }
            SyntaxKind.CHARACTER_LITERAL -> {
                "```kotlin\n$text\n```\n\n**Type:** `Char`"
            }
            SyntaxKind.BOOLEAN_LITERAL -> {
                "```kotlin\n$text\n```\n\n**Type:** `Boolean`"
            }
            SyntaxKind.NULL_LITERAL -> {
                "```kotlin\nnull\n```\n\n**Type:** `Nothing?`"
            }
            else -> null
        }
    }

    private fun parseIntegerLiteral(text: String): String {
        val cleaned = text.replace("_", "").removeSuffix("L").removeSuffix("l")
        return try {
            when {
                cleaned.startsWith("0x", ignoreCase = true) ->
                    cleaned.substring(2).toLong(16).toString()
                cleaned.startsWith("0b", ignoreCase = true) ->
                    cleaned.substring(2).toLong(2).toString()
                else -> cleaned
            }
        } catch (e: NumberFormatException) {
            text
        }
    }

    companion object {
        private val KEYWORD_DESCRIPTIONS = mapOf(
            SyntaxKind.FUN to "Declares a function",
            SyntaxKind.VAL to "Declares a read-only property or local variable",
            SyntaxKind.VAR to "Declares a mutable property or local variable",
            SyntaxKind.CLASS to "Declares a class",
            SyntaxKind.INTERFACE to "Declares an interface",
            SyntaxKind.OBJECT to "Declares a singleton object or companion object",
            SyntaxKind.IF to "Conditional expression",
            SyntaxKind.ELSE to "Alternative branch of a conditional",
            SyntaxKind.WHEN to "Pattern matching expression",
            SyntaxKind.FOR to "Iterates over a range, array, or iterable",
            SyntaxKind.WHILE to "Loop that executes while condition is true",
            SyntaxKind.DO to "Loop that executes at least once",
            SyntaxKind.RETURN to "Returns from a function",
            SyntaxKind.BREAK to "Terminates the nearest enclosing loop",
            SyntaxKind.CONTINUE to "Proceeds to the next iteration of a loop",
            SyntaxKind.THROW to "Throws an exception",
            SyntaxKind.TRY to "Begins a try-catch-finally block",
            SyntaxKind.CATCH to "Handles an exception",
            SyntaxKind.FINALLY to "Block that always executes after try/catch",
            SyntaxKind.THIS to "Reference to current instance",
            SyntaxKind.SUPER to "Reference to superclass",
            SyntaxKind.SUSPEND to "Marks a function as suspendable for coroutines",
            SyntaxKind.INLINE to "Inlines the function at call sites",
            SyntaxKind.DATA to "Generates equals, hashCode, toString, copy",
            SyntaxKind.SEALED to "Restricts subclass hierarchy",
            SyntaxKind.OVERRIDE to "Overrides a member from a supertype",
            SyntaxKind.OPEN to "Allows the class or member to be overridden",
            SyntaxKind.ABSTRACT to "Declares an abstract class or member",
            SyntaxKind.FINAL to "Prevents overriding",
            SyntaxKind.PRIVATE to "Visible only in the containing declaration",
            SyntaxKind.PROTECTED to "Visible in the class and subclasses",
            SyntaxKind.INTERNAL to "Visible within the same module",
            SyntaxKind.PUBLIC to "Visible everywhere",
            SyntaxKind.COMPANION to "Declares a companion object",
            SyntaxKind.LATEINIT to "Allows non-null property initialization to be deferred",
            SyntaxKind.CONST to "Compile-time constant",
            SyntaxKind.TYPEALIAS to "Creates a type alias"
        )
    }
}

private val SyntaxKind.isKeyword: Boolean
    get() = name.all { it.isUpperCase() || it == '_' } &&
            this != SyntaxKind.SIMPLE_IDENTIFIER &&
            this != SyntaxKind.ERROR

private val SyntaxKind.isLiteral: Boolean
    get() = this == SyntaxKind.INTEGER_LITERAL ||
            this == SyntaxKind.REAL_LITERAL ||
            this == SyntaxKind.STRING_LITERAL ||
            this == SyntaxKind.MULTI_LINE_STRING_LITERAL ||
            this == SyntaxKind.CHARACTER_LITERAL ||
            this == SyntaxKind.BOOLEAN_LITERAL ||
            this == SyntaxKind.NULL_LITERAL
