package org.appdevforall.codeonthego.lsp.kotlin.server.providers

import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.semantic.DiagnosticCode
import org.appdevforall.codeonthego.lsp.kotlin.server.AnalysisScheduler
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either

class CodeActionProvider(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val analysisScheduler: AnalysisScheduler
) {
    fun provideCodeActions(
        uri: String,
        range: Range,
        diagnostics: List<Diagnostic>
    ): List<Either<Command, CodeAction>> {
        val state = documentManager.get(uri) ?: return emptyList()

        analysisScheduler.analyzeSync(uri)

        val actions = mutableListOf<Either<Command, CodeAction>>()

        for (diagnostic in diagnostics) {
            val diagnosticActions = createActionsForDiagnostic(uri, diagnostic, state.content)
            actions.addAll(diagnosticActions.map { Either.forRight(it) })
        }

        return actions
    }

    private fun createActionsForDiagnostic(
        uri: String,
        diagnostic: Diagnostic,
        content: String
    ): List<CodeAction> {
        val code = diagnostic.code?.left ?: return emptyList()

        return when (code) {
            DiagnosticCode.UNRESOLVED_REFERENCE.id -> createImportActions(uri, diagnostic)
            DiagnosticCode.UNSAFE_CALL.id -> createSafeCallActions(uri, diagnostic, content)
            DiagnosticCode.VAL_REASSIGNMENT.id -> createChangeToVarAction(uri, diagnostic, content)
            else -> emptyList()
        }
    }

    private fun createImportActions(uri: String, diagnostic: Diagnostic): List<CodeAction> {
        val unresolvedName = extractUnresolvedName(diagnostic.message) ?: return emptyList()

        val candidates = projectIndex.findBySimpleName(unresolvedName)
            .filter { it.fqName != unresolvedName }
            .distinctBy { it.fqName }
            .take(5)

        if (candidates.isEmpty()) {
            val stdlibCandidates = projectIndex.getStdlibIndex()
                ?.findBySimpleName(unresolvedName)
                ?.distinctBy { it.fqName }
                ?.take(5)
                ?: emptyList()

            return stdlibCandidates.map { candidate ->
                createImportAction(uri, candidate.fqName)
            }
        }

        return candidates.map { candidate ->
            createImportAction(uri, candidate.fqName)
        }
    }

    private fun createImportAction(uri: String, fqName: String): CodeAction {
        val state = documentManager.get(uri) ?: return CodeAction().apply {
            title = "Import '$fqName'"
            kind = CodeActionKind.QuickFix
        }

        val importPosition = findImportInsertPosition(state.content)

        val edit = TextEdit(
            Range(Position(importPosition, 0), Position(importPosition, 0)),
            "import $fqName\n"
        )

        return CodeAction().apply {
            title = "Import '$fqName'"
            kind = CodeActionKind.QuickFix
            this.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
        }
    }

    private fun findImportInsertPosition(content: String): Int {
        val lines = content.lines()

        var lastImportLine = -1
        var packageLine = -1

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("package ") -> packageLine = index
                trimmed.startsWith("import ") -> lastImportLine = index
            }
        }

        return when {
            lastImportLine >= 0 -> lastImportLine + 1
            packageLine >= 0 -> packageLine + 2
            else -> 0
        }
    }

    private fun createSafeCallActions(
        uri: String,
        diagnostic: Diagnostic,
        content: String
    ): List<CodeAction> {
        val range = diagnostic.range
        val lines = content.lines()

        if (range.start.line >= lines.size) return emptyList()

        val line = lines[range.start.line]
        val dotIndex = findDotBeforePosition(line, range.start.character)
        if (dotIndex < 0) return emptyList()

        val safeCallEdit = TextEdit(
            Range(Position(range.start.line, dotIndex), Position(range.start.line, dotIndex + 1)),
            "?."
        )

        val safeCallAction = CodeAction().apply {
            title = "Use safe call (?.)"
            kind = CodeActionKind.QuickFix
            edit = WorkspaceEdit(mapOf(uri to listOf(safeCallEdit)))
            diagnostics = listOf(diagnostic)
        }

        val assertionEdit = TextEdit(
            Range(Position(range.start.line, dotIndex), Position(range.start.line, dotIndex + 1)),
            "!!."
        )

        val assertionAction = CodeAction().apply {
            title = "Use non-null assertion (!!.)"
            kind = CodeActionKind.QuickFix
            edit = WorkspaceEdit(mapOf(uri to listOf(assertionEdit)))
            diagnostics = listOf(diagnostic)
        }

        return listOf(safeCallAction, assertionAction)
    }

    private fun findDotBeforePosition(line: String, position: Int): Int {
        for (i in position downTo 0) {
            if (i < line.length && line[i] == '.') {
                return i
            }
        }
        return -1
    }

    private fun createChangeToVarAction(
        uri: String,
        diagnostic: Diagnostic,
        content: String
    ): List<CodeAction> {
        val state = documentManager.get(uri) ?: return emptyList()
        val symbolTable = state.symbolTable ?: return emptyList()

        val position = diagnostic.range.start
        val lines = content.lines()
        if (position.line >= lines.size) return emptyList()

        val valPosition = findValDeclaration(content, position.line, position.character)
        if (valPosition == null) return emptyList()

        val edit = TextEdit(
            Range(
                Position(valPosition.first, valPosition.second),
                Position(valPosition.first, valPosition.second + 3)
            ),
            "var"
        )

        return listOf(CodeAction().apply {
            title = "Change 'val' to 'var'"
            kind = CodeActionKind.QuickFix
            this.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
            diagnostics = listOf(diagnostic)
        })
    }

    private fun findValDeclaration(content: String, startLine: Int, startChar: Int): Pair<Int, Int>? {
        val lines = content.lines()

        for (line in startLine downTo maxOf(0, startLine - 20)) {
            val lineContent = lines.getOrNull(line) ?: continue
            val valIndex = lineContent.indexOf("val ")
            if (valIndex >= 0) {
                return line to valIndex
            }
        }

        return null
    }

    private fun extractUnresolvedName(message: String): String? {
        val pattern = "Unresolved reference: (.+)".toRegex()
        return pattern.find(message)?.groupValues?.getOrNull(1)
    }
}
