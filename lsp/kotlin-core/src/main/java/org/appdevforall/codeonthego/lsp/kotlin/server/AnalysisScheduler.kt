package org.appdevforall.codeonthego.lsp.kotlin.server

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.appdevforall.codeonthego.lsp.kotlin.index.FileIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.KotlinParser
import org.appdevforall.codeonthego.lsp.kotlin.parser.ParseResult
import org.appdevforall.codeonthego.lsp.kotlin.semantic.AnalysisContext
import org.appdevforall.codeonthego.lsp.kotlin.semantic.Diagnostic
import org.appdevforall.codeonthego.lsp.kotlin.semantic.DiagnosticCode
import org.appdevforall.codeonthego.lsp.kotlin.semantic.DiagnosticSeverity
import org.appdevforall.codeonthego.lsp.kotlin.semantic.SemanticAnalyzer
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolBuilder
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "AnalysisScheduler"

/**
 * Coordinates background analysis of documents.
 *
 * AnalysisScheduler manages the parsing and semantic analysis pipeline,
 * debouncing rapid edits and publishing diagnostics when analysis completes.
 *
 * ## Architecture
 *
 * ```
 * Document Edit
 *      │
 *      ▼
 * ┌─────────────┐
 * │  Debounce   │  (100ms delay for rapid typing)
 * └─────────────┘
 *      │
 *      ▼
 * ┌─────────────┐
 * │   Parse     │  (tree-sitter, fast)
 * └─────────────┘
 *      │
 *      ▼
 * ┌─────────────┐
 * │  Symbols    │  (extract declarations)
 * └─────────────┘
 *      │
 *      ▼
 * ┌─────────────┐
 * │  Semantic   │  (type checking, resolution)
 * └─────────────┘
 *      │
 *      ▼
 * ┌─────────────┐
 * │ Diagnostics │  (publish to client)
 * └─────────────┘
 * ```
 *
 * ## Thread Safety
 *
 * All operations are thread-safe. Analysis runs on a dedicated
 * coroutine dispatcher to avoid blocking the main LSP thread.
 *
 * @param documentManager Source of document content
 * @param projectIndex Project symbol index to update
 * @param debounceMs Delay before processing edits (default 100ms)
 */
class AnalysisScheduler(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val debounceMs: Long = 500L
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ktlsp-analysis-thread").apply {
            isDaemon = true
        }
    }
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + analysisDispatcher)

    private val parserLock = Any()
    @Volatile
    private var _parser: KotlinParser? = null

    private fun getParser(): KotlinParser {
        _parser?.let { return it }
        synchronized(parserLock) {
            _parser?.let { return it }
            return KotlinParser().also { _parser = it }
        }
    }

    private val analysisRequests = Channel<AnalysisRequest>(Channel.CONFLATED)
    private val pendingAnalysis = ConcurrentHashMap<String, Job>()

    private val _diagnosticsFlow = MutableSharedFlow<DiagnosticsUpdate>(
        replay = 0,
        extraBufferCapacity = 64
    )

    val diagnosticsFlow: SharedFlow<DiagnosticsUpdate> = _diagnosticsFlow.asSharedFlow()

    @Volatile
    private var isRunning = false

    @Volatile
    private var diagnosticsListener: DiagnosticsListener? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            for (request in analysisRequests) {
                processRequest(request)
            }
        }
    }

    fun stop() {
        isRunning = false
        pendingAnalysis.values.forEach { it.cancel() }
        pendingAnalysis.clear()
        scope.cancel()
        _parser?.close()
        _parser = null
        analysisExecutor.shutdown()
    }

    fun setDiagnosticsListener(listener: DiagnosticsListener?) {
        diagnosticsListener = listener
    }

    fun scheduleAnalysis(uri: String, priority: AnalysisPriority = AnalysisPriority.NORMAL) {
        if (!isRunning) return

        pendingAnalysis[uri]?.cancel()

        val job = scope.launch {
            if (debounceMs > 0 && priority != AnalysisPriority.IMMEDIATE) {
                delay(debounceMs)
            }
            analysisRequests.send(AnalysisRequest(uri, priority))
        }

        pendingAnalysis[uri] = job
    }

    fun scheduleImmediateAnalysis(uri: String) {
        scheduleAnalysis(uri, AnalysisPriority.IMMEDIATE)
    }

    fun analyzeSync(uri: String): AnalysisResult? {
        val state = documentManager.get(uri) ?: return null
        return runBlocking(analysisDispatcher) {
            performAnalysis(state)
        }
    }

    suspend fun ensureAnalyzed(uri: String): AnalysisResult? {
        val state = documentManager.get(uri) ?: return null

        if (state.isAnalyzed) {
            return AnalysisResult(
                uri = uri,
                version = state.version,
                parseResult = state.parseResult,
                symbolTable = state.symbolTable,
                analysisContext = state.analysisContext,
                fileIndex = state.fileIndex,
                diagnostics = state.diagnostics,
                analysisTimeMs = 0
            )
        }

        return withContext(analysisDispatcher) {
            performAnalysis(state)
        }
    }

    fun cancelAnalysis(uri: String) {
        pendingAnalysis.remove(uri)?.cancel()
    }

    fun cancelAll() {
        pendingAnalysis.values.forEach { it.cancel() }
        pendingAnalysis.clear()
    }

    fun isPending(uri: String): Boolean {
        return pendingAnalysis[uri]?.isActive == true
    }

    fun pendingCount(): Int {
        return pendingAnalysis.count { it.value.isActive }
    }

    private suspend fun processRequest(request: AnalysisRequest) {
        val state = documentManager.get(request.uri) ?: return

        val result = performAnalysis(state)

        val update = DiagnosticsUpdate(
            uri = request.uri,
            version = state.version,
            diagnostics = result.diagnostics
        )

        _diagnosticsFlow.emit(update)
        diagnosticsListener?.onDiagnostics(update)

        pendingAnalysis.remove(request.uri)
    }

    private fun performAnalysis(state: DocumentState): AnalysisResult {
        val startTime = System.currentTimeMillis()


        val parseResult = getParser().parse(state.content)
        state.setParsed(parseResult)

        for (err in parseResult.syntaxErrors) {
            Log.d(TAG, "[ANALYSIS]   SyntaxError: '${err.message}', range=${err.range}, text='${err.errorNodeText}'")
        }

        val symbolTable = SymbolBuilder.build(parseResult.tree, state.filePath)

        val syntaxErrorRanges = parseResult.syntaxErrors.map { it.range }

        val analysisContext = AnalysisContext(
            tree = parseResult.tree,
            symbolTable = symbolTable,
            filePath = state.filePath,
            stdlibIndex = projectIndex.getStdlibIndex(),
            projectIndex = projectIndex,
            syntaxErrorRanges = syntaxErrorRanges
        )

        for (syntaxError in parseResult.syntaxErrors) {
            analysisContext.diagnostics.error(
                DiagnosticCode.SYNTAX_ERROR,
                syntaxError.range,
                syntaxError.message,
                filePath = state.filePath
            )
        }

        val semanticAnalyzer = SemanticAnalyzer(analysisContext)
        try {
            semanticAnalyzer.analyze()
        } catch (e: Exception) {
            Log.e(TAG, "Semantic analysis failed: ${e.message}", e)
        }

        val allDiagnostics = analysisContext.diagnostics.diagnostics
        val diagnostics = allDiagnostics.filter { diagnostic ->
            if (diagnostic.code == DiagnosticCode.SYNTAX_ERROR) {
                true
            } else {
                !syntaxErrorRanges.any { errorRange -> diagnostic.range.overlaps(errorRange) }
            }
        }
        Log.d(TAG, "Diagnostics generated: ${diagnostics.size} (filtered from ${allDiagnostics.size}, syntax errors: ${parseResult.syntaxErrors.size})")
        for (diag in diagnostics.take(10)) {
            Log.d(TAG, "  Diagnostic: code=${diag.code}, severity=${diag.severity}, message='${diag.message.take(60)}', range=${diag.range}")
        }

        val fileIndex = FileIndex.fromSymbolTable(symbolTable)

        state.setAnalyzed(symbolTable, analysisContext, fileIndex, diagnostics)

        projectIndex.updateFile(fileIndex)

        val elapsedMs = System.currentTimeMillis() - startTime

        return AnalysisResult(
            uri = state.uri,
            version = state.version,
            parseResult = parseResult,
            symbolTable = symbolTable,
            analysisContext = analysisContext,
            fileIndex = fileIndex,
            diagnostics = diagnostics,
            analysisTimeMs = elapsedMs
        )
    }
}

enum class AnalysisPriority {
    LOW,
    NORMAL,
    HIGH,
    IMMEDIATE
}

private data class AnalysisRequest(
    val uri: String,
    val priority: AnalysisPriority
)

data class AnalysisResult(
    val uri: String,
    val version: Int,
    val parseResult: ParseResult?,
    val symbolTable: SymbolTable?,
    val analysisContext: AnalysisContext?,
    val fileIndex: FileIndex?,
    val diagnostics: List<Diagnostic>,
    val analysisTimeMs: Long
) {
    val isSuccessful: Boolean get() = parseResult != null && symbolTable != null
    val hasErrors: Boolean get() = diagnostics.any {
        it.severity == DiagnosticSeverity.ERROR
    }
}

data class DiagnosticsUpdate(
    val uri: String,
    val version: Int,
    val diagnostics: List<Diagnostic>
)

fun interface DiagnosticsListener {
    fun onDiagnostics(update: DiagnosticsUpdate)
}
