package org.appdevforall.codeonthego.lsp.kotlin.server.providers

import org.appdevforall.codeonthego.lsp.kotlin.parser.TextRange
import org.appdevforall.codeonthego.lsp.kotlin.semantic.Diagnostic
import org.appdevforall.codeonthego.lsp.kotlin.semantic.DiagnosticSeverity
import org.appdevforall.codeonthego.lsp.kotlin.server.AnalysisScheduler
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentManager
import org.appdevforall.codeonthego.lsp.kotlin.server.toLsp4j
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.services.LanguageClient

/**
 * Provides diagnostic publishing for errors and warnings.
 *
 * DiagnosticProvider converts internal diagnostics to LSP format
 * and publishes them to the client.
 *
 * ## Diagnostic Lifecycle
 *
 * 1. Document is edited
 * 2. AnalysisScheduler runs analysis
 * 3. SemanticAnalyzer produces diagnostics
 * 4. DiagnosticProvider converts and publishes to client
 *
 * ## Diagnostic Types
 *
 * - **Error**: Syntax errors, type mismatches, unresolved references
 * - **Warning**: Unused variables, deprecated API usage
 * - **Info**: Code style suggestions
 * - **Hint**: Refactoring opportunities
 */
class DiagnosticProvider(
    private val documentManager: DocumentManager,
    private val analysisScheduler: AnalysisScheduler
) {
    @Volatile
    private var client: LanguageClient? = null

    fun setClient(client: LanguageClient) {
        this.client = client
    }

    fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        val lspDiagnostics = diagnostics.map { it.toLsp4j() }

        client?.publishDiagnostics(PublishDiagnosticsParams(
            uri,
            lspDiagnostics
        ))
    }

    fun publishDiagnosticsWithVersion(uri: String, version: Int, diagnostics: List<Diagnostic>) {
        val lspDiagnostics = diagnostics.map { it.toLsp4j() }

        client?.publishDiagnostics(PublishDiagnosticsParams(
            uri,
            lspDiagnostics,
            version
        ))
    }

    fun clearDiagnostics(uri: String) {
        client?.publishDiagnostics(PublishDiagnosticsParams(
            uri,
            emptyList()
        ))
    }

    fun getDiagnostics(uri: String): List<Diagnostic> {
        val state = documentManager.get(uri) ?: return emptyList()

        analysisScheduler.analyzeSync(uri)

        return state.diagnostics
    }

    fun getDiagnosticsInRange(uri: String, range: Range): List<Diagnostic> {
        return getDiagnostics(uri).filter { diagnostic ->
            rangesOverlap(diagnostic.range.toLspRange(), range)
        }
    }

    fun getErrorCount(uri: String): Int {
        return getDiagnostics(uri).count { it.severity == DiagnosticSeverity.ERROR }
    }

    fun getWarningCount(uri: String): Int {
        return getDiagnostics(uri).count { it.severity == DiagnosticSeverity.WARNING }
    }

    fun hasErrors(uri: String): Boolean {
        return getDiagnostics(uri).any { it.severity == DiagnosticSeverity.ERROR }
    }

    fun refreshAllDiagnostics() {
        for (uri in documentManager.openUris) {
            val state = documentManager.get(uri) ?: continue
            publishDiagnosticsWithVersion(uri, state.version, state.diagnostics)
        }
    }

    private fun rangesOverlap(a: Range, b: Range): Boolean {
        if (a.end.line < b.start.line) return false
        if (a.start.line > b.end.line) return false

        if (a.end.line == b.start.line && a.end.character < b.start.character) return false
        if (a.start.line == b.end.line && a.start.character > b.end.character) return false

        return true
    }
}

private fun TextRange.toLspRange(): Range {
    return Range(
        Position(startLine, startColumn),
        Position(endLine, endColumn)
    )
}
