package org.appdevforall.codeonthego.lsp.kotlin.server

import android.util.Log
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.server.providers.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

private const val TAG = "KtTextDocService"

/**
 * Handles text document related LSP requests.
 *
 * KotlinTextDocumentService processes all document lifecycle events
 * and provides language features like completion, hover, and navigation.
 */
class KotlinTextDocumentService(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val analysisScheduler: AnalysisScheduler
) : TextDocumentService {

    @Volatile
    private var client: LanguageClient? = null

    private val completionProvider = CompletionProvider(documentManager, projectIndex, analysisScheduler)
    private val hoverProvider = HoverProvider(documentManager, projectIndex, analysisScheduler)
    private val definitionProvider = DefinitionProvider(documentManager, projectIndex, analysisScheduler)
    private val documentSymbolProvider = DocumentSymbolProvider(documentManager, analysisScheduler)
    private val diagnosticProvider = DiagnosticProvider(documentManager, analysisScheduler)
    private val semanticTokenProvider = SemanticTokenProvider(documentManager, projectIndex, analysisScheduler)
    private val codeActionProvider = CodeActionProvider(documentManager, projectIndex, analysisScheduler)

    fun setClient(client: LanguageClient) {
        this.client = client
        diagnosticProvider.setClient(client)
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val document = params.textDocument
        Log.d(TAG, "didOpen: uri=${document.uri}, version=${document.version}, contentLength=${document.text.length}")

        val existing = documentManager.get(document.uri)
        if (existing != null) {
            Log.w(TAG, "  WARNING: document already open! existing version=${existing.version}, contentLength=${existing.content.length}")
        }

        documentManager.open(document.uri, document.text, document.version)
        analysisScheduler.scheduleImmediateAnalysis(document.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version

        Log.d(TAG, "didChange: uri=$uri, version=$version, changes=${params.contentChanges.size}")

        for ((index, change) in params.contentChanges.withIndex()) {
            if (change.range == null) {
                Log.d(TAG, "  change[$index]: full sync, text=${change.text.length} chars")
                documentManager.update(uri, change.text, version)
            } else {
                val range = change.range
                Log.d(TAG, "  change[$index]: incremental, range=${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}, text='${change.text}' (${change.text.length} chars)")
                documentManager.applyChange(
                    uri = uri,
                    startLine = range.start.line,
                    startChar = range.start.character,
                    endLine = range.end.line,
                    endChar = range.end.character,
                    newText = change.text,
                    version = version
                )
            }
        }

        val state = documentManager.get(uri)
        Log.d(TAG, "  after changes: contentLength=${state?.content?.length ?: -1}")

        analysisScheduler.scheduleAnalysis(uri)
    }

    /**
     * Applies a document change using direct character indices.
     * This bypasses line/column to offset conversion for more reliable sync.
     */
    fun didChangeByIndex(
        uri: String,
        startIndex: Int,
        endIndex: Int,
        newText: String,
        version: Int
    ) {
        Log.d(TAG, "didChangeByIndex: uri=$uri, version=$version, indices=$startIndex-$endIndex, text='$newText' (${newText.length} chars)")

        documentManager.applyChangeByIndex(
            uri = uri,
            startIndex = startIndex,
            endIndex = endIndex,
            newText = newText,
            version = version
        )

        val state = documentManager.get(uri)
        Log.d(TAG, "  after change: contentLength=${state?.content?.length ?: -1}")

        analysisScheduler.scheduleAnalysis(uri)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        analysisScheduler.cancelAnalysis(uri)
        documentManager.close(uri)
        projectIndex.removeFile(uri)

        client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri

        if (params.text != null) {
            val state = documentManager.get(uri)
            if (state != null) {
                documentManager.update(uri, params.text, state.version + 1)
            }
        }

        analysisScheduler.scheduleImmediateAnalysis(uri)
    }

    override fun completion(
        params: CompletionParams
    ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return CompletableFuture.supplyAsync {
            val result = completionProvider.provideCompletions(
                uri = params.textDocument.uri,
                line = params.position.line,
                character = params.position.character,
                context = params.context
            )
            Either.forRight(result)
        }
    }

    override fun resolveCompletionItem(
        unresolved: CompletionItem
    ): CompletableFuture<CompletionItem> {
        return CompletableFuture.supplyAsync {
            completionProvider.resolveCompletionItem(unresolved)
        }
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        return CompletableFuture.supplyAsync {
            hoverProvider.provideHover(
                uri = params.textDocument.uri,
                line = params.position.line,
                character = params.position.character
            )
        }
    }

    override fun definition(
        params: DefinitionParams
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.supplyAsync {
            val locations = definitionProvider.provideDefinition(
                uri = params.textDocument.uri,
                line = params.position.line,
                character = params.position.character
            )
            Either.forLeft(locations)
        }
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFuture.supplyAsync {
            definitionProvider.provideReferences(
                uri = params.textDocument.uri,
                line = params.position.line,
                character = params.position.character,
                includeDeclaration = params.context?.isIncludeDeclaration == true
            )
        }
    }

    override fun documentSymbol(
        params: DocumentSymbolParams
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return CompletableFuture.supplyAsync {
            val symbols = documentSymbolProvider.provideDocumentSymbols(params.textDocument.uri)
            symbols.map { Either.forRight(it) }
        }
    }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp?> {
        return CompletableFuture.supplyAsync {
            completionProvider.provideSignatureHelp(
                uri = params.textDocument.uri,
                line = params.position.line,
                character = params.position.character
            )
        }
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return CompletableFuture.supplyAsync {
            codeActionProvider.provideCodeActions(
                uri = params.textDocument.uri,
                range = params.range,
                diagnostics = params.context?.diagnostics ?: emptyList()
            )
        }
    }

    override fun documentHighlight(
        params: DocumentHighlightParams
    ): CompletableFuture<List<DocumentHighlight>> {
        return CompletableFuture.supplyAsync {
            definitionProvider.provideHighlights(
                uri = params.textDocument.uri,
                line = params.position.line,
                character = params.position.character
            )
        }
    }

    override fun semanticTokensFull(
        params: SemanticTokensParams
    ): CompletableFuture<SemanticTokens> {
        return CompletableFuture.supplyAsync {
            semanticTokenProvider.provideSemanticTokens(params.textDocument.uri)
                ?: SemanticTokens(emptyList())
        }
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        return CompletableFuture.completedFuture(null)
    }

    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> {
        return CompletableFuture.completedFuture(null)
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFuture.completedFuture(emptyList())
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFuture.completedFuture(emptyList())
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFuture.completedFuture(emptyList())
    }
}
