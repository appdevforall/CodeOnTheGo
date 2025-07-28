/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin

interface EditorExtension : IPlugin {
    fun provideCompletionItems(context: CompletionContext): List<CompletionItem>
    fun provideCodeActions(context: CodeActionContext): List<CodeAction>
    fun provideHover(position: TextPosition): HoverInfo?
    fun provideSyntaxHighlighting(): SyntaxHighlighter?
}

data class CompletionContext(
    val document: TextDocument,
    val position: Position,
    val triggerCharacter: String?
)

data class CodeActionContext(
    val document: TextDocument,
    val range: TextRange,
    val diagnostics: List<Diagnostic>
)

data class TextPosition(val line: Int, val column: Int)
data class Position(val line: Int, val character: Int)
data class TextRange(val start: Position, val end: Position)

interface TextDocument {
    val uri: String
    val text: String
    val lineCount: Int
    fun getLine(lineNumber: Int): String
}

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind,
    val detail: String? = null,
    val documentation: String? = null,
    val insertText: String? = null
)

enum class CompletionItemKind {
    TEXT, METHOD, FUNCTION, CONSTRUCTOR, FIELD, VARIABLE, 
    CLASS, INTERFACE, MODULE, PROPERTY, UNIT, VALUE, 
    ENUM, KEYWORD, SNIPPET, COLOR, FILE, REFERENCE
}

data class CodeAction(
    val title: String,
    val kind: CodeActionKind,
    val isPreferred: Boolean = false,
    val execute: () -> Unit
)

enum class CodeActionKind {
    QUICK_FIX, REFACTOR, REFACTOR_EXTRACT, REFACTOR_INLINE, 
    REFACTOR_REWRITE, SOURCE, SOURCE_ORGANIZE_IMPORTS
}

data class HoverInfo(
    val contents: String,
    val range: TextRange? = null
)

interface SyntaxHighlighter {
    fun highlight(text: String): List<HighlightRegion>
}

data class HighlightRegion(
    val start: Int,
    val end: Int,
    val type: HighlightType
)

enum class HighlightType {
    KEYWORD, STRING, COMMENT, NUMBER, OPERATOR, 
    IDENTIFIER, TYPE, FUNCTION, PARAMETER
}

data class Diagnostic(
    val range: TextRange,
    val message: String,
    val severity: DiagnosticSeverity,
    val code: String? = null
)

enum class DiagnosticSeverity {
    ERROR, WARNING, INFORMATION, HINT
}