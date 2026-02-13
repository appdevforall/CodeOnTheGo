package org.appdevforall.codeonthego.lsp.kotlin.server

import org.appdevforall.codeonthego.lsp.kotlin.index.ClasspathIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.ClasspathIndexer
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.StdlibIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.StdlibIndexLoader
import org.appdevforall.codeonthego.lsp.kotlin.server.providers.SemanticTokenProvider
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess
import org.appdevforall.codeonthego.lsp.kotlin.semantic.Diagnostic as KtDiagnostic
import org.appdevforall.codeonthego.lsp.kotlin.semantic.DiagnosticSeverity as KtDiagnosticSeverity

private const val TAG = "KotlinLanguageServer"

/**
 * Kotlin Language Server implementation.
 *
 * KotlinLanguageServer is the main entry point for the LSP protocol,
 * implementing the standard Language Server Protocol using LSP4J.
 *
 * ## Features
 *
 * - Text document synchronization (incremental)
 * - Code completion
 * - Hover information
 * - Go to definition
 * - Find references
 * - Document symbols
 * - Diagnostics
 * - Signature help
 *
 * ## Usage
 *
 * ```kotlin
 * val server = KotlinLanguageServer()
 *
 * // For in-process communication
 * val launcher = LSPLauncher.createServerLauncher(
 *     server,
 *     inputStream,
 *     outputStream
 * )
 * launcher.startListening()
 * ```
 */
class KotlinLanguageServer : LanguageServer, LanguageClientAware {

    private val documentManager = DocumentManager()
    private val projectIndex = ProjectIndex()
    private val analysisScheduler = AnalysisScheduler(documentManager, projectIndex)
    private val classpathIndexer = ClasspathIndexer()

    private lateinit var textDocumentService: KotlinTextDocumentService
    private lateinit var workspaceService: KotlinWorkspaceService

    @Volatile
    private var client: LanguageClient? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var shutdownRequested = false

    private var rootUri: String? = null
    private var workspaceFolders: List<WorkspaceFolder> = emptyList()

    init {
        textDocumentService = KotlinTextDocumentService(
            documentManager = documentManager,
            projectIndex = projectIndex,
            analysisScheduler = analysisScheduler
        )

        workspaceService = KotlinWorkspaceService(
            documentManager = documentManager,
            projectIndex = projectIndex,
            analysisScheduler = analysisScheduler
        )
    }

    fun loadStdlibIndex(inputStream: InputStream) {
        val stdlibIndex = StdlibIndexLoader.loadFromStream(inputStream)
        projectIndex.setStdlibIndex(stdlibIndex)
    }

    fun loadStdlibIndex(stdlibIndex: StdlibIndex) {
        projectIndex.setStdlibIndex(stdlibIndex)
    }

    fun useMinimalStdlibIndex() {
        projectIndex.setStdlibIndex(StdlibIndexLoader.createMinimalIndex())
    }

    fun setClasspath(files: List<File>) {
        val index = classpathIndexer.index(files)
        projectIndex.setClasspathIndex(index)
    }

    fun setClasspathAsync(files: List<File>): CompletableFuture<ClasspathIndex> {
        return CompletableFuture.supplyAsync {
            val index = classpathIndexer.index(files)
            projectIndex.setClasspathIndex(index)
            index
        }
    }

    fun addToClasspath(files: List<File>) {
        val existingIndex = projectIndex.getClasspathIndex() ?: ClasspathIndex.empty()
        val updatedIndex = classpathIndexer.indexIncremental(files, existingIndex)
        projectIndex.setClasspathIndex(updatedIndex)
    }

    fun getClasspathIndex(): ClasspathIndex? {
        return projectIndex.getClasspathIndex()
    }

    fun hasAndroidSdkClasses(): Boolean {
        val index = projectIndex.getClasspathIndex() ?: return false
        return listOf("android.widget.Button", "android.view.View", "android.app.Activity")
            .any { index.findByFqName(it) != null }
    }

    override fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.setClient(client)
        workspaceService.setClient(client)

        analysisScheduler.setDiagnosticsListener { update ->
            val lsp4jDiags = update.diagnostics.map { it.toLsp4j() }
            client.publishDiagnostics(
                PublishDiagnosticsParams(
                    update.uri,
                    lsp4jDiags,
                    update.version
                )
            )
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        rootUri = params.rootUri
        workspaceFolders = params.workspaceFolders ?: emptyList()

        val capabilities = ServerCapabilities().apply {
            textDocumentSync = Either.forRight(TextDocumentSyncOptions().apply {
                openClose = true
                change = TextDocumentSyncKind.Incremental
                save = Either.forRight(SaveOptions().apply {
                    includeText = true
                })
            })

            completionProvider = CompletionOptions().apply {
                triggerCharacters = listOf(".", ":", "@", "(")
                resolveProvider = true
            }

            hoverProvider = Either.forLeft(true)

            definitionProvider = Either.forLeft(true)

            referencesProvider = Either.forLeft(true)

            documentSymbolProvider = Either.forLeft(true)

            workspaceSymbolProvider = Either.forLeft(true)

            signatureHelpProvider = SignatureHelpOptions().apply {
                triggerCharacters = listOf("(", ",")
                retriggerCharacters = listOf(",")
            }

            codeActionProvider = Either.forLeft(true)

            semanticTokensProvider = SemanticTokensWithRegistrationOptions().apply {
                legend = SemanticTokenProvider.LEGEND
                full = Either.forLeft(true)
            }
        }

        val serverInfo = ServerInfo("Kotlin Language Server", "0.1.0")

        analysisScheduler.start()
        initialized = true

        return CompletableFuture.completedFuture(InitializeResult(capabilities, serverInfo))
    }

    override fun initialized(params: InitializedParams?) {
        client?.logMessage(MessageParams(MessageType.Info, "Kotlin Language Server initialized"))
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdownRequested = true
        analysisScheduler.stop()
        documentManager.clear()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        if (!shutdownRequested) {
            exitProcess(1)
        } else {
            exitProcess(0)
        }
    }

    override fun getTextDocumentService(): TextDocumentService {
        return textDocumentService
    }

    override fun getWorkspaceService(): WorkspaceService {
        return workspaceService
    }

    fun getDocumentManager(): DocumentManager = documentManager

    fun getProjectIndex(): ProjectIndex = projectIndex

    fun getAnalysisScheduler(): AnalysisScheduler = analysisScheduler

    fun didChangeByIndex(uri: String, startIndex: Int, endIndex: Int, newText: String, version: Int) {
        textDocumentService.didChangeByIndex(uri, startIndex, endIndex, newText, version)
    }

    fun isInitialized(): Boolean = initialized

    fun isShutdownRequested(): Boolean = shutdownRequested

    fun getClient(): LanguageClient? = client

    fun getRootUri(): String? = rootUri

    fun getWorkspaceFolders(): List<WorkspaceFolder> = workspaceFolders
}

fun KtDiagnostic.toLsp4j(): Diagnostic {
    return Diagnostic().apply {
        range = Range(
            Position(this@toLsp4j.range.startLine, this@toLsp4j.range.startColumn),
            Position(this@toLsp4j.range.endLine, this@toLsp4j.range.endColumn)
        )
        severity = when (this@toLsp4j.severity) {
            KtDiagnosticSeverity.ERROR -> DiagnosticSeverity.Error
            KtDiagnosticSeverity.WARNING -> DiagnosticSeverity.Warning
            KtDiagnosticSeverity.INFO -> DiagnosticSeverity.Information
            KtDiagnosticSeverity.HINT -> DiagnosticSeverity.Hint
        }
        source = "ktlsp"
        message = this@toLsp4j.message
        code = Either.forLeft(this@toLsp4j.code.name)
    }
}
