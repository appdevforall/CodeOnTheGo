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
import java.io.File

// Simple data classes for editor functionality
data class Position(val line: Int, val column: Int)
data class Range(val start: Position, val end: Position)
data class CompletionItem(val label: String, val detail: String?, val insertText: String)

interface EditorExtension : IPlugin {
    fun provideCompletionItems(context: CompletionContext): List<CompletionItem>
    fun provideCodeActions(context: CodeActionContext): List<CodeAction>
    fun provideHover(context: HoverContext): HoverInfo?
    fun onDocumentOpen(file: File) {}
    fun onDocumentClose(file: File) {}
    fun onDocumentChange(file: File, changes: List<TextChange>) {}
}

data class CompletionContext(
    val editor: Any, // IEditor reference to avoid Android dependency
    val file: File,
    val position: Position,
    val triggerCharacter: String?
)

data class CodeActionContext(
    val editor: Any, // IEditor reference to avoid Android dependency
    val file: File,
    val range: Range,
    val diagnostics: List<Diagnostic>
)

data class HoverContext(
    val editor: Any, // IEditor reference to avoid Android dependency
    val file: File,
    val position: Position
)

data class CodeAction(
    val title: String,
    val description: String?,
    val kind: CodeActionKind,
    val isPreferred: Boolean = false,
    val execute: () -> Unit
)

enum class CodeActionKind {
    QUICK_FIX,
    REFACTOR,
    REFACTOR_EXTRACT,
    REFACTOR_INLINE,
    REFACTOR_REWRITE,
    SOURCE,
    SOURCE_ORGANIZE_IMPORTS,
    SOURCE_FIX_ALL
}

data class HoverInfo(
    val contents: String,
    val range: Range?
)

data class TextChange(
    val range: Range,
    val newText: String
)

data class Diagnostic(
    val range: Range,
    val message: String,
    val severity: DiagnosticSeverity,
    val source: String?
)

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFORMATION,
    HINT
}