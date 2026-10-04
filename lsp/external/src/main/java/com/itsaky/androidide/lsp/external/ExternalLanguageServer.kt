package com.itsaky.androidide.lsp.external

import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
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
import com.itsaky.androidide.lsp.models.MatchLevel
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.projectDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionItemCapabilities
import org.eclipse.lsp4j.DefinitionCapabilities
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingCapabilities
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RangeFormattingCapabilities
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferencesCapabilities
import org.eclipse.lsp4j.SelectionRangeCapabilities
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SignatureHelpCapabilities
import org.eclipse.lsp4j.SynchronizationCapabilities
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.eclipse.lsp4j.CompletionParams as LspCompletionParams
import org.eclipse.lsp4j.DefinitionParams as LspDefinitionParams
import org.eclipse.lsp4j.ReferenceParams as LspReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams as LspSignatureHelpParams

class ExternalLanguageServer(
	override val serverId: String,
	private val languageId: String,
	fileExtensions: Set<String>,
	private val initializationOptions: Map<String, Any?>,
	private val processFactory: (File?) -> Process,
	private val indentation: () -> Indentation,
	private val uiExecutor: Executor,
) : ILanguageServer {
	private val extensions = fileExtensions.map { it.lowercase() }.toSet()
	private val messageExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "lsp-$serverId") }
	private val lifecycleLock = Any()
	private val openDocuments = ConcurrentHashMap<Path, OpenDocument>()
	private val diagnostics = ConcurrentHashMap<Path, List<Diagnostic>>()

	private var closed = false
	private var crashCount = 0

	@Volatile
	private var session: Session? = null

	@Volatile
	private var languageClient: ILanguageClient? = null

	@Volatile
	private var workspaceRoot: File? = null

	override val client: ILanguageClient?
		get() = languageClient

	init {
		EventBus.getDefault().register(this)
	}

	override fun connectClient(client: ILanguageClient?) {
		languageClient = client
	}

	override fun applySettings(settings: IServerSettings?) = Unit

	override fun setupWithProject(workspace: Workspace) {
		workspaceRoot = workspace.rootProject.projectDir
	}

	override fun shutdown() {
		synchronized(lifecycleLock) {
			if (closed) return
			closed = true
		}
		EventBus.getDefault().unregister(this)
		messageExecutor.execute {
			val ending = session ?: return@execute
			session = null
			stopProcess(ending)
		}
		messageExecutor.shutdown()
	}

	override fun complete(params: CompletionParams?): CompletionResult {
		if (params == null || !handles(params.file) || params.prefix.isNullOrEmpty()) return CompletionResult.EMPTY
		val lspParams =
			LspCompletionParams().apply {
				textDocument = TextDocumentIdentifier(params.file.toLspUri())
				position = params.position.toLsp()
			}
		val content = params.content?.toString()
		val result =
			request(params.cancelChecker, REQUEST_TIMEOUT_SECONDS, { it.completionProvider != null }) {
				if (content != null) syncDocument(params.file, content)
				it.textDocumentService.completion(lspParams)
			} ?: return CompletionResult.EMPTY
		val items = if (result.isLeft) result.left else result.right.items
		val prefix = (params.prefix ?: "").substringAfterLast('.')
		return CompletionResult(items.map { it.toIde(prefix) }.filter { it.matchLevel != MatchLevel.NO_MATCH })
	}

	override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
		if (!handles(params.file)) return DefinitionResult.empty()
		val lspParams = LspDefinitionParams(TextDocumentIdentifier(params.file.toLspUri()), params.position.toLsp())
		val result =
			withContext(Dispatchers.IO) {
				request(params.cancelChecker, REQUEST_TIMEOUT_SECONDS, { it.definitionProvider.isEnabled() }) {
					it.textDocumentService.definition(lspParams)
				}
			}
		return DefinitionResult(result.toIdeLocations())
	}

	override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
		if (!handles(params.file)) return ReferenceResult.empty()
		val lspParams =
			LspReferenceParams(
				TextDocumentIdentifier(params.file.toLspUri()),
				params.position.toLsp(),
				ReferenceContext(params.includeDeclaration),
			)
		val result =
			withContext(Dispatchers.IO) {
				request(params.cancelChecker, REQUEST_TIMEOUT_SECONDS, { it.referencesProvider.isEnabled() }) {
					it.textDocumentService.references(lspParams)
				}
			}
		return ReferenceResult(result?.map { it.toIde() } ?: emptyList())
	}

	override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
		if (!handles(params.file)) return SignatureHelp.empty()
		val lspParams = LspSignatureHelpParams(TextDocumentIdentifier(params.file.toLspUri()), params.position.toLsp())
		val result =
			withContext(Dispatchers.IO) {
				request(params.cancelChecker, REQUEST_TIMEOUT_SECONDS, { it.signatureHelpProvider != null }) {
					it.textDocumentService.signatureHelp(lspParams)
				}
			}
		return result?.toIde() ?: SignatureHelp.empty()
	}

	override suspend fun expandSelection(params: ExpandSelectionParams): Range {
		if (!handles(params.file)) return params.selection
		val lspParams =
			SelectionRangeParams(TextDocumentIdentifier(params.file.toLspUri()), listOf(params.selection.start.toLsp()))
		val result =
			withContext(Dispatchers.IO) {
				request(ICancelChecker.NOOP, REQUEST_TIMEOUT_SECONDS, { it.selectionRangeProvider.isEnabled() }) {
					it.textDocumentService.selectionRange(lspParams)
				}
			}
		var candidate = result?.firstOrNull()
		while (candidate != null) {
			val range = candidate.range.toIde()
			if (range.start <= params.selection.start && range.end >= params.selection.end && range != params.selection) {
				return range
			}
			candidate = candidate.parent
		}
		return params.selection
	}

	override suspend fun analyze(file: Path): DiagnosticResult {
		if (!handles(file)) return DiagnosticResult.NO_UPDATE
		val raw = diagnostics[file] ?: return DiagnosticResult.NO_UPDATE
		val text = openDocuments[file]?.text ?: withContext(Dispatchers.IO) { file.toFile().readText() }
		return raw.toDiagnosticResult(file, serverId, text)
	}

	override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
		if (params == null) return CodeFormatResult.NONE
		val content = params.content.toString()
		val options = indentation().let { FormattingOptions(it.tabSize, it.insertSpaces) }
		val wholeDocument = params.range.start.index <= 0 && params.range.end.index >= content.length
		val edits =
			request(
				ICancelChecker.NOOP,
				FORMAT_TIMEOUT_SECONDS,
				{ it.documentFormattingProvider.isEnabled() || it.documentRangeFormattingProvider.isEnabled() },
			) { server ->
				val file =
					openDocuments.entries.firstOrNull { it.value.text == content }?.key
						?: run {
							log.warn("Language server {} cannot format: no open document matches the editor text", serverId)
							return@request CompletableFuture.completedFuture(null)
						}
				val document = TextDocumentIdentifier(file.toLspUri())
				val capabilities = session?.capabilities
				if (!wholeDocument && capabilities?.documentRangeFormattingProvider.isEnabled()) {
					server.textDocumentService.rangeFormatting(
						DocumentRangeFormattingParams(document, options, params.range.toLsp()),
					)
				} else {
					server.textDocumentService.formatting(DocumentFormattingParams(document, options))
				}
			}
		if (edits.isNullOrEmpty()) return CodeFormatResult.NONE
		return CodeFormatResult(true, indexedTextEdits = edits.toIndexedEdits(content))
	}

	@Subscribe(threadMode = ThreadMode.POSTING)
	@Suppress("unused")
	fun onDocumentOpen(event: DocumentOpenEvent) {
		val file = event.openedFile
		if (!handles(file)) return
		submit {
			openDocuments[file] = OpenDocument(event.version, event.text)
			val current = session
			if (current == null) {
				start()
			} else {
				current.server.textDocumentService.didOpen(openParams(file, openDocuments.getValue(file)))
			}
		}
	}

	@Subscribe(threadMode = ThreadMode.POSTING)
	@Suppress("unused")
	fun onDocumentChange(event: DocumentChangeEvent) {
		val file = event.changedFile
		if (!handles(file)) return
		submit { syncDocument(file, FileManager.getDocumentContents(file)) }
	}

	@Subscribe(threadMode = ThreadMode.POSTING)
	@Suppress("unused")
	fun onDocumentSave(event: DocumentSaveEvent) {
		val file = event.savedFile
		if (!handles(file)) return
		submit {
			if (!openDocuments.containsKey(file)) return@submit
			session?.server?.textDocumentService?.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(file.toLspUri())))
		}
	}

	@Subscribe(threadMode = ThreadMode.POSTING)
	@Suppress("unused")
	fun onDocumentClose(event: DocumentCloseEvent) {
		val file = event.closedFile
		if (!handles(file)) return
		submit {
			openDocuments.remove(file) ?: return@submit
			session?.server?.textDocumentService?.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(file.toLspUri())))
		}
	}

	private fun syncDocument(
		file: Path,
		text: String,
	) {
		val document = openDocuments[file] ?: return
		if (document.text == text) return
		val synced = OpenDocument(document.version + 1, text)
		openDocuments[file] = synced
		val current = session ?: return
		if (current.syncKind == TextDocumentSyncKind.None) return
		current.server.textDocumentService.didChange(
			DidChangeTextDocumentParams(
				VersionedTextDocumentIdentifier(file.toLspUri(), synced.version),
				listOf(TextDocumentContentChangeEvent(text)),
			),
		)
	}

	private fun handles(file: Path): Boolean = file.toFile().extension.lowercase() in extensions

	private fun submit(task: () -> Unit): Boolean =
		synchronized(lifecycleLock) {
			if (closed) return false
			messageExecutor.execute(task)
			true
		}

	private fun <T> request(
		cancelChecker: ICancelChecker,
		timeoutSeconds: Long,
		supported: (ServerCapabilities) -> Boolean,
		send: (LanguageServer) -> CompletableFuture<T>,
	): T? {
		val current = session ?: return null
		if (!supported(current.capabilities)) return null

		val sent = CompletableFuture<CompletableFuture<T>>()
		val submitted =
			submit {
				runCatching { send(current.server) }.fold(sent::complete, sent::completeExceptionally)
			}
		if (!submitted) return null

		val onCancel: () -> Unit = { sent.thenAccept { it.cancel(true) } }
		cancelChecker.invokeOnCancel(onCancel)
		return try {
			sent.get(timeoutSeconds, TimeUnit.SECONDS).get(timeoutSeconds, TimeUnit.SECONDS)
		} catch (e: CancellationException) {
			null
		} catch (e: InterruptedException) {
			sent.thenAccept { it.cancel(true) }
			Thread.currentThread().interrupt()
			null
		} catch (e: TimeoutException) {
			sent.thenAccept { it.cancel(true) }
			log.warn("Request to language server {} timed out after {}s", serverId, timeoutSeconds)
			null
		} catch (e: ExecutionException) {
			log.error("Request to language server {} failed", serverId, e.cause ?: e)
			null
		} finally {
			cancelChecker.removeOnCancel(onCancel)
		}
	}

	private fun start(): Session? {
		val process =
			try {
				processFactory(workspaceRoot)
			} catch (e: IOException) {
				log.error("Unable to start language server {}", serverId, e)
				return null
			}
		drainErrors(process)

		val launcher = LSPLauncher.createClientLauncher(ClientBridge(), process.inputStream, process.outputStream)
		val listening = launcher.startListening()
		val server = launcher.remoteProxy
		val capabilities =
			try {
				server.initialize(initializeParams()).get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS).capabilities
			} catch (e: ExecutionException) {
				log.error("Language server {} failed to initialize", serverId, e.cause ?: e)
				process.destroy()
				return null
			} catch (e: TimeoutException) {
				log.error("Language server {} did not initialize within {}s", serverId, INITIALIZE_TIMEOUT_SECONDS)
				process.destroy()
				return null
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				process.destroy()
				return null
			}
		server.initialized(InitializedParams())

		val started = Session(process, server, capabilities)
		session = started
		openDocuments.forEach { (file, document) -> server.textDocumentService.didOpen(openParams(file, document)) }
		watchForExit(started, listening)
		log.info("Language server {} started", serverId)
		return started
	}

	private fun watchForExit(
		started: Session,
		listening: Future<Void>,
	) {
		Thread({
			try {
				listening.get()
			} catch (e: ExecutionException) {
				log.warn("Language server {} connection failed", serverId, e.cause ?: e)
			} catch (e: CancellationException) {
				log.debug("Language server {} connection cancelled", serverId)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
			}
			submit { onSessionEnded(started) }
		}, "lsp-$serverId-exit").apply {
			isDaemon = true
			start()
		}
	}

	private fun onSessionEnded(ended: Session) {
		if (session !== ended) return
		session = null
		ended.process.destroy()
		crashCount++
		log.warn("Language server {} exited unexpectedly ({}/{})", serverId, crashCount, MAX_RESTARTS)
		if (crashCount < MAX_RESTARTS && openDocuments.isNotEmpty()) {
			start()
		}
	}

	private fun stopProcess(ending: Session) {
		try {
			ending.server.shutdown().get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			ending.server.exit()
		} catch (e: ExecutionException) {
			log.debug("Language server {} did not shut down cleanly", serverId, e.cause ?: e)
		} catch (e: TimeoutException) {
			log.debug("Language server {} did not acknowledge shutdown", serverId)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
		}
		ending.process.destroy()
	}

	private fun drainErrors(process: Process) {
		Thread({
			try {
				process.errorStream.bufferedReader().forEachLine { log.debug("[{}] {}", serverId, it) }
			} catch (e: IOException) {
				log.debug("Language server {} stderr closed", serverId)
			}
		}, "lsp-$serverId-stderr").apply {
			isDaemon = true
			start()
		}
	}

	private fun initializeParams(): InitializeParams =
		InitializeParams().apply {
			val root = workspaceRoot?.toPath()
			rootUri = root?.toLspUri()
			workspaceFolders = root?.let { listOf(WorkspaceFolder(it.toLspUri(), it.fileName.toString())) }
			initializationOptions = this@ExternalLanguageServer.initializationOptions.takeIf { it.isNotEmpty() }
			capabilities =
				ClientCapabilities().apply {
					textDocument =
						TextDocumentClientCapabilities().apply {
							synchronization = SynchronizationCapabilities(false, false, true)
							completion = CompletionCapabilities(CompletionItemCapabilities(false))
							publishDiagnostics = PublishDiagnosticsCapabilities()
							definition = DefinitionCapabilities()
							references = ReferencesCapabilities()
							signatureHelp = SignatureHelpCapabilities()
							formatting = FormattingCapabilities()
							rangeFormatting = RangeFormattingCapabilities()
							selectionRange = SelectionRangeCapabilities()
						}
				}
		}

	private fun openParams(
		file: Path,
		document: OpenDocument,
	): DidOpenTextDocumentParams = DidOpenTextDocumentParams(TextDocumentItem(file.toLspUri(), languageId, document.version, document.text))

	private inner class ClientBridge : LanguageClient {
		override fun publishDiagnostics(params: PublishDiagnosticsParams) {
			val file = params.uri.toPath()
			diagnostics[file] = params.diagnostics
			val result = params.diagnostics.toDiagnosticResult(file, serverId, openDocuments[file]?.text)
			uiExecutor.execute { languageClient?.publishDiagnostics(result) }
		}

		override fun telemetryEvent(value: Any?) = Unit

		override fun showMessage(params: MessageParams) = logMessage(params)

		override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
			log.info("[{}] {}", serverId, params.message)
			return CompletableFuture.completedFuture(null)
		}

		override fun logMessage(params: MessageParams) {
			when (params.type) {
				MessageType.Error -> log.error("[{}] {}", serverId, params.message)
				MessageType.Warning -> log.warn("[{}] {}", serverId, params.message)
				MessageType.Info -> log.info("[{}] {}", serverId, params.message)
				MessageType.Log, null -> log.debug("[{}] {}", serverId, params.message)
			}
		}
	}

	private class Session(
		val process: Process,
		val server: LanguageServer,
		val capabilities: ServerCapabilities,
	) {
		val syncKind: TextDocumentSyncKind =
			capabilities.textDocumentSync?.let { if (it.isLeft) it.left else it.right.change } ?: TextDocumentSyncKind.None
	}

	data class Indentation(
		val tabSize: Int,
		val insertSpaces: Boolean,
	)

	private data class OpenDocument(
		val version: Int,
		val text: String,
	)

	companion object {
		private val log = LoggerFactory.getLogger(ExternalLanguageServer::class.java)
		private const val INITIALIZE_TIMEOUT_SECONDS = 60L
		private const val REQUEST_TIMEOUT_SECONDS = 10L
		private const val FORMAT_TIMEOUT_SECONDS = 20L
		private const val SHUTDOWN_TIMEOUT_SECONDS = 3L
		private const val MAX_RESTARTS = 3
	}
}

private fun org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean, *>?.isEnabled(): Boolean = this != null && (isRight || left == true)
