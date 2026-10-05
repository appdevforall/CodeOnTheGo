package com.itsaky.androidide.lsp.external

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.FormatCodeParams
import com.itsaky.androidide.lsp.models.PerformCodeActionParams
import com.itsaky.androidide.lsp.models.ShowDocumentParams
import com.itsaky.androidide.lsp.models.ShowDocumentResult
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.CompletionParams as LspCompletionParams
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range as LspRange

class ExternalLanguageServerTest {
	private val servers = mutableListOf<ExternalLanguageServer>()
	private val file: Path = Files.createTempFile("sample", ".py")
	private var restoreHandler: () -> Unit = {}

	@After
	fun tearDown() {
		servers.forEach { it.shutdown() }
		Files.deleteIfExists(file)
		restoreHandler()
	}

	@Test
	fun `documents opened before initialization reach the server`() {
		val fake = FakeServer()
		val server = newServer { fake.launch() }

		server.onDocumentOpen(DocumentOpenEvent(file, "print(1)\n", 0))

		assertThat(fake.opened.awaitBriefly()).isTrue()
		assertThat(fake.openedText).containsExactly("print(1)\n")
	}

	@Test
	fun `changes are sent as full text with an increasing version`() {
		val fake = FakeServer()
		val server = newServer { fake.launch() }
		server.onDocumentOpen(DocumentOpenEvent(file, "a", 0))
		fake.opened.awaitBriefly()

		FileManager.onDocumentOpen(DocumentOpenEvent(file, "a", 0))
		val change = change("ab", 1)
		FileManager.onDocumentContentChange(change)
		server.onDocumentChange(change)

		assertThat(fake.changed.await(5, TimeUnit.SECONDS)).isTrue()
		assertThat(fake.changes).containsExactly(1 to "ab")
	}

	@Test
	fun `completion inserts the text edit and not the label`() {
		val fake = FakeServer()
		fake.completion =
			CompletionItem("append()").apply {
				textEdit = Either.forLeft(TextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 2)), "append"))
			}
		val server = newServer { fake.launch() }
		server.onDocumentOpen(DocumentOpenEvent(file, "ap", 0))
		fake.opened.awaitBriefly()

		val result =
			server.complete(
				CompletionParams(Position(0, 2, 2), file, ICancelChecker.NOOP).apply { prefix = "ap" },
			)

		assertThat(result.items.map { it.ideLabel to it.insertText }).containsExactly("append()" to "append")
	}

	@Test
	fun `completion sends the editor text the server has not seen yet`() {
		val fake = FakeServer()
		fake.completion = CompletionItem("greet")
		val server = newServer { fake.launch() }
		server.onDocumentOpen(DocumentOpenEvent(file, "g = 1\n", 0))
		fake.opened.awaitBriefly()

		val result =
			server.complete(
				CompletionParams(Position(1, 4, 10), file, ICancelChecker.NOOP).apply {
					content = "g = 1\ng.gr"
					prefix = "g.gr"
				},
			)

		assertThat(fake.textAtCompletion).isEqualTo("g = 1\ng.gr")
		assertThat(result.items.map { it.ideLabel }).containsExactly("greet")
	}

	@Test
	fun `completion is not requested when nothing has been typed`() {
		val fake = FakeServer()
		fake.completion = CompletionItem("AbstractSet")
		val server = newServer { fake.launch() }
		server.onDocumentOpen(DocumentOpenEvent(file, "def f():\n", 0))
		fake.opened.awaitBriefly()

		val result =
			server.complete(
				CompletionParams(Position(0, 8, 8), file, ICancelChecker.NOOP).apply {
					content = "def f():\n"
					prefix = ""
				},
			)

		assertThat(result.items).isEmpty()
		assertThat(fake.textAtCompletion).isNull()
	}

	@Test
	fun `format edits are ordered so applying them in sequence is correct`() {
		val content = "a=1\nb=2\n"
		val fake = FakeServer()
		fake.formatting =
			listOf(
				TextEdit(LspRange(LspPosition(0, 1), LspPosition(0, 2)), " = "),
				TextEdit(LspRange(LspPosition(1, 1), LspPosition(1, 2)), " = "),
			)
		val server = newServer { fake.launch() }
		server.onDocumentOpen(DocumentOpenEvent(file, content, 0))
		fake.opened.awaitBriefly()

		val result = server.formatCode(FormatCodeParams(content, Range(Position(0, 0, 0), Position(2, 0, content.length))))

		val formatted = StringBuilder(content)
		result.indexedTextEdits.forEach { formatted.replace(it.start, it.end, it.newText.toString()) }
		assertThat(formatted.toString()).isEqualTo("a = 1\nb = 2\n")
	}

	@Test
	fun `published diagnostics carry indices into the open document`() {
		val fake = FakeServer()
		val client = RecordingClient()
		val server = newServer { fake.launch() }
		server.connectClient(client)
		server.onDocumentOpen(DocumentOpenEvent(file, "x = 1\nundefined_name\n", 0))
		fake.opened.awaitBriefly()

		fake.publish(file, Diagnostic(LspRange(LspPosition(1, 0), LspPosition(1, 14)), "undefined"))

		val item = client.nextDiagnostics().diagnostics.single()
		assertThat(item.range.start.index).isEqualTo(6)
		assertThat(item.range.end.index).isEqualTo(20)
	}

	@Test
	fun `a server that keeps crashing is restarted a bounded number of times`() {
		val starts = AtomicInteger()
		val exited = CountDownLatch(3)
		val server =
			newServer {
				starts.incrementAndGet()
				FakeServer(exitAfterInitialized = true, onExit = exited::countDown).launch()
			}

		server.onDocumentOpen(DocumentOpenEvent(file, "", 0))

		assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue()
		Thread.sleep(1_000)
		assertThat(starts.get()).isEqualTo(3)
	}

	@Test
	fun `a server that reports no capabilities is left stopped instead of crashing the IDE`() {
		val uncaught = captureUncaught()
		val fake = FakeServer(capabilities = null)
		val server = newServer { fake.launch() }

		server.onDocumentOpen(DocumentOpenEvent(file, "print(1)\n", 0))
		Thread.sleep(1_000)

		assertThat(uncaught).isEmpty()
		assertThat(
			server.complete(CompletionParams(Position(0, 1, 1), file, ICancelChecker.NOOP).apply { prefix = "p" }),
		).isEqualTo(CompletionResult.EMPTY)
	}

	@Test
	fun `a process that cannot be started is logged instead of crashing the IDE`() {
		val uncaught = captureUncaught()
		val server = newServer { throw IllegalArgumentException("Invalid environment variable name: \"A=B\"") }

		server.onDocumentOpen(DocumentOpenEvent(file, "", 0))
		Thread.sleep(1_000)

		assertThat(uncaught).isEmpty()
	}

	private fun captureUncaught(): MutableList<Throwable> {
		val uncaught = CopyOnWriteArrayList<Throwable>()
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
		restoreHandler = { Thread.setDefaultUncaughtExceptionHandler(previous) }
		return uncaught
	}

	private fun newServer(processFactory: () -> Process): ExternalLanguageServer =
		ExternalLanguageServer(
			serverId = "test.python",
			languageId = "python",
			fileExtensions = setOf("py"),
			initializationOptions = emptyMap(),
			processFactory = { processFactory() },
			indentation = { ExternalLanguageServer.Indentation(4, true) },
			uiExecutor = { it.run() },
		).also(servers::add)

	private fun change(
		text: String,
		version: Int,
	) = DocumentChangeEvent(file, text, text, version, ChangeType.NEW_TEXT, 0, Range.NONE)

	private fun CountDownLatch.awaitBriefly(): Boolean = await(5, TimeUnit.SECONDS)

	private class FakeServer(
		private val exitAfterInitialized: Boolean = false,
		private val onExit: () -> Unit = {},
		private val capabilities: ServerCapabilities? =
			ServerCapabilities().apply {
				setTextDocumentSync(TextDocumentSyncKind.Full)
				completionProvider = CompletionOptions()
				setDocumentFormattingProvider(true)
			},
	) : LanguageServer,
		LanguageClientAware,
		TextDocumentService,
		WorkspaceService {
		val opened = CountDownLatch(1)
		val changed = CountDownLatch(1)
		val openedText = CopyOnWriteArrayList<String>()
		val changes = CopyOnWriteArrayList<Pair<Int, String>>()
		var completion: CompletionItem? = null

		@Volatile
		var textAtCompletion: String? = null
		var formatting: List<TextEdit> = emptyList()
		private lateinit var client: LanguageClient
		private lateinit var toClient: PipedOutputStream

		fun launch(): Process {
			val clientToServer = PipedOutputStream()
			val serverIn = PipedInputStream(clientToServer, PIPE_SIZE)
			toClient = PipedOutputStream()
			val clientIn = PipedInputStream(toClient, PIPE_SIZE)
			val launcher = LSPLauncher.createServerLauncher(this, serverIn, toClient)
			connect(launcher.remoteProxy)
			launcher.startListening()
			return PipeProcess(clientIn, clientToServer)
		}

		fun publish(
			file: Path,
			diagnostic: Diagnostic,
		) = client.publishDiagnostics(PublishDiagnosticsParams(file.toUri().toString(), listOf(diagnostic)))

		override fun connect(client: LanguageClient) {
			this.client = client
		}

		override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
			CompletableFuture.completedFuture(capabilities?.let(::InitializeResult) ?: InitializeResult())

		override fun initialized(params: org.eclipse.lsp4j.InitializedParams) {
			if (exitAfterInitialized) {
				toClient.close()
				onExit()
			}
		}

		override fun shutdown(): CompletableFuture<Any> = CompletableFuture.completedFuture(null)

		override fun exit() = Unit

		override fun getTextDocumentService(): TextDocumentService = this

		override fun getWorkspaceService(): WorkspaceService = this

		override fun didOpen(params: DidOpenTextDocumentParams) {
			openedText += params.textDocument.text
			opened.countDown()
		}

		override fun didChange(params: DidChangeTextDocumentParams) {
			changes += params.textDocument.version to params.contentChanges.single().text
			changed.countDown()
		}

		override fun didClose(params: DidCloseTextDocumentParams) = Unit

		override fun didSave(params: DidSaveTextDocumentParams) = Unit

		override fun completion(position: LspCompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
			textAtCompletion = changes.lastOrNull()?.second ?: openedText.lastOrNull()
			return CompletableFuture.completedFuture(Either.forLeft(listOfNotNull(completion)))
		}

		override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> =
			CompletableFuture.completedFuture(formatting)

		override fun didChangeConfiguration(params: org.eclipse.lsp4j.DidChangeConfigurationParams) = Unit

		override fun didChangeWatchedFiles(params: org.eclipse.lsp4j.DidChangeWatchedFilesParams) = Unit
	}

	private class PipeProcess(
		private val input: InputStream,
		private val output: OutputStream,
	) : Process() {
		private val errors = PipedInputStream(PipedOutputStream().also { it.close() })

		override fun getOutputStream(): OutputStream = output

		override fun getInputStream(): InputStream = input

		override fun getErrorStream(): InputStream = errors

		override fun waitFor(): Int = 0

		override fun exitValue(): Int = 0

		override fun destroy() {
			output.close()
			input.close()
		}
	}

	private class RecordingClient : ILanguageClient {
		private val published = java.util.concurrent.LinkedBlockingQueue<DiagnosticResult>()

		fun nextDiagnostics(): DiagnosticResult = checkNotNull(published.poll(5, TimeUnit.SECONDS))

		override fun getDiagnosticAt(
			file: File,
			line: Int,
			column: Int,
		): DiagnosticItem? = null

		override fun performCodeAction(params: PerformCodeActionParams) = Unit

		override fun publishDiagnostics(result: DiagnosticResult) {
			published.put(result)
		}

		override fun showDocument(params: ShowDocumentParams): ShowDocumentResult = ShowDocumentResult(false)

		override fun showLocations(locations: List<Location>) = Unit
	}

	companion object {
		private const val PIPE_SIZE = 1 shl 16
	}
}
