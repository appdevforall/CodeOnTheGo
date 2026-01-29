package org.appdevforall.codeonthego.lsp.kotlin.server

import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbolKind
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

/**
 * Handles workspace-level LSP requests.
 *
 * KotlinWorkspaceService processes workspace operations like
 * symbol search and configuration changes.
 */
class KotlinWorkspaceService(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val analysisScheduler: AnalysisScheduler
) : WorkspaceService {

    @Volatile
    private var client: LanguageClient? = null

    private var workspaceFolders: List<WorkspaceFolder> = emptyList()

    fun setClient(client: LanguageClient) {
        this.client = client
    }

    fun setWorkspaceFolders(folders: List<WorkspaceFolder>) {
        workspaceFolders = folders
    }

    override fun symbol(
        params: WorkspaceSymbolParams
    ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        return CompletableFuture.supplyAsync {
            val query = params.query
            if (query.isBlank()) {
                return@supplyAsync Either.forLeft(emptyList())
            }

            val symbols = projectIndex.findByPrefix(query, limit = 50)

            val symbolInfos = symbols.mapNotNull { indexed ->
                val filePath = indexed.filePath ?: return@mapNotNull null

                val startLine = indexed.startLine ?: 0
                val startColumn = indexed.startColumn ?: 0
                val endLine = indexed.endLine ?: startLine
                val endColumn = indexed.endColumn ?: (startColumn + indexed.name.length)

                SymbolInformation().apply {
                    name = indexed.name
                    kind = indexed.kind.toSymbolKind()
                    location = Location(
                        filePath,
                        Range(Position(startLine, startColumn), Position(endLine, endColumn))
                    )
                    containerName = indexed.containingClass ?: indexed.packageName
                    if (indexed.deprecated) {
                        @Suppress("DEPRECATION")
                        deprecated = true
                    }
                }
            }

            Either.forLeft(symbolInfos)
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        client?.logMessage(MessageParams(MessageType.Info, "Configuration changed"))
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        for (change in params.changes) {
            when (change.type) {
                FileChangeType.Created -> handleFileCreated(change.uri)
                FileChangeType.Changed -> handleFileChanged(change.uri)
                FileChangeType.Deleted -> handleFileDeleted(change.uri)
                else -> {}
            }
        }
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        val added = params.event.added ?: emptyList()
        val removed = params.event.removed ?: emptyList()

        val updatedFolders = workspaceFolders.toMutableList()
        updatedFolders.removeAll(removed)
        updatedFolders.addAll(added)
        workspaceFolders = updatedFolders

        for (folder in removed) {
            handleWorkspaceFolderRemoved(folder)
        }

        for (folder in added) {
            handleWorkspaceFolderAdded(folder)
        }
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any?> {
        return CompletableFuture.completedFuture(null)
    }

    private fun handleFileCreated(uri: String) {
        if (!uri.endsWith(".kt") && !uri.endsWith(".kts")) return
        analysisScheduler.scheduleAnalysis(uri)
    }

    private fun handleFileChanged(uri: String) {
        if (!uri.endsWith(".kt") && !uri.endsWith(".kts")) return

        if (!documentManager.isOpen(uri)) {
            analysisScheduler.scheduleAnalysis(uri)
        }
    }

    private fun handleFileDeleted(uri: String) {
        documentManager.close(uri)
        projectIndex.removeFile(uri)
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    private fun handleWorkspaceFolderAdded(folder: WorkspaceFolder) {
        client?.logMessage(MessageParams(MessageType.Info, "Workspace folder added: ${folder.uri}"))
    }

    private fun handleWorkspaceFolderRemoved(folder: WorkspaceFolder) {
        val folderUri = folder.uri

        for (uri in documentManager.openUris) {
            if (uri.startsWith(folderUri)) {
                documentManager.close(uri)
                projectIndex.removeFile(uri)
            }
        }

        client?.logMessage(MessageParams(MessageType.Info, "Workspace folder removed: ${folder.uri}"))
    }
}

private fun IndexedSymbolKind.toSymbolKind(): SymbolKind {
    return when (this) {
        IndexedSymbolKind.CLASS -> SymbolKind.Class
        IndexedSymbolKind.INTERFACE -> SymbolKind.Interface
        IndexedSymbolKind.OBJECT -> SymbolKind.Object
        IndexedSymbolKind.ENUM_CLASS -> SymbolKind.Enum
        IndexedSymbolKind.ANNOTATION_CLASS -> SymbolKind.Class
        IndexedSymbolKind.DATA_CLASS -> SymbolKind.Class
        IndexedSymbolKind.VALUE_CLASS -> SymbolKind.Class
        IndexedSymbolKind.FUNCTION -> SymbolKind.Function
        IndexedSymbolKind.PROPERTY -> SymbolKind.Property
        IndexedSymbolKind.CONSTRUCTOR -> SymbolKind.Constructor
        IndexedSymbolKind.TYPE_ALIAS -> SymbolKind.TypeParameter
    }
}
