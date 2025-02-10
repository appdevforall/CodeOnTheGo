package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.models.CodeFormatResult
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DefinitionParams
import com.itsaky.androidide.lsp.models.DefinitionResult
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.ExpandSelectionParams
import com.itsaky.androidide.lsp.models.FormatCodeParams
import com.itsaky.androidide.lsp.models.LSPFailure
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.api.Project
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.nio.file.Path

class KotlinLanguageServer : ILanguageServer {

    override var client: ILanguageClient? = null
        private set

    private var _settings: IServerSettings? = null
    private var selectedFile: Path? = null
    private var process: Process? = null

    override val serverId: String = SERVER_ID

    companion object {
        const val SERVER_ID = "ide.lsp.kotlin"
        private val log = LoggerFactory.getLogger(KotlinLanguageServer::class.java)
        private const val KOTLIN_LSP_PATH = "./kotlin-language-server"

    }

    init {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        startLanguageServer()
    }

    override fun shutdown() {
        process?.destroy()
        EventBus.getDefault().unregister(this)
    }

    override fun connectClient(client: ILanguageClient?) {
        this.client = client
    }

    override fun applySettings(settings: IServerSettings?) {
        this._settings = settings
    }

    override fun setupWithProject(project: Project) {
        startLanguageServer()
    }

    private fun startLanguageServer() {
        try {
            val processBuilder = ProcessBuilder(KOTLIN_LSP_PATH)
                .redirectErrorStream(true)
            process = processBuilder.start()
            log.info("Kotlin Language Server started at $KOTLIN_LSP_PATH")
        } catch (e: Exception) {
            log.error("Failed to start Kotlin Language Server", e)
        }
    }

    override fun complete(params: CompletionParams?): CompletionResult {
        return CompletionResult.EMPTY // TODO: Implement completion logic
    }

    override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
        return ReferenceResult(emptyList()) // TODO: Implement findReferences logic
    }

    override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
        return DefinitionResult(emptyList()) // TODO: Implement findDefinition logic
    }

    override suspend fun expandSelection(params: ExpandSelectionParams): Range {
        return params.selection // TODO: Implement expandSelection logic
    }

    override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
        return SignatureHelp(emptyList(), -1, -1) // TODO: Implement signatureHelp logic
    }

    override suspend fun analyze(file: Path): DiagnosticResult {
        return DiagnosticResult.NO_UPDATE // TODO: Implement analyze logic
    }

    override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
        return CodeFormatResult(false, mutableListOf()) // TODO: Implement formatCode logic
    }

    override fun handleFailure(failure: LSPFailure?): Boolean {
        return false // TODO: Handle failure cases
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    @Suppress("unused")
    fun onFileOpened(event: DocumentOpenEvent) {
        selectedFile = event.openedFile
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    @Suppress("unused")
    fun onFileClosed(event: DocumentCloseEvent) {
        selectedFile = null
    }
}
