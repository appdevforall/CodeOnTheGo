package com.itsaky.androidide.compose.preview

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.compose.preview.compiler.CompileDiagnostic
import com.itsaky.androidide.compose.preview.compiler.CompilerDaemon
import com.itsaky.androidide.compose.preview.compiler.ComposeClasspathManager
import com.itsaky.androidide.compose.preview.compiler.ComposeCompiler
import com.itsaky.androidide.compose.preview.compiler.ComposeDexCompiler
import com.itsaky.androidide.compose.preview.compiler.DexCache
import com.itsaky.androidide.compose.preview.compiler.PreviewSourceTransformer
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

sealed class PreviewState {
    data object Idle : PreviewState()
    data object Initializing : PreviewState()
    data object Compiling : PreviewState()
    data object Empty : PreviewState()
    data class Ready(
        val dexFile: File,
        val className: String,
        val previewConfigs: List<PreviewConfig>,
        val runtimeDex: File?
    ) : PreviewState()
    data class Error(
        val message: String,
        val diagnostics: List<CompileDiagnostic> = emptyList()
    ) : PreviewState()
}

enum class DisplayMode { ALL, SINGLE }

data class PreviewConfig(
    val functionName: String,
    val heightDp: Int? = null,
    val widthDp: Int? = null
)

@OptIn(FlowPreview::class)
class ComposePreviewViewModel : ViewModel() {

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    private val _displayMode = MutableStateFlow(DisplayMode.ALL)
    val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

    private val _selectedPreview = MutableStateFlow<String?>(null)
    val selectedPreview: StateFlow<String?> = _selectedPreview.asStateFlow()

    private val _availablePreviews = MutableStateFlow<List<String>>(emptyList())
    val availablePreviews: StateFlow<List<String>> = _availablePreviews.asStateFlow()

    private val sourceChanges = MutableSharedFlow<SourceUpdate>()

    private var classpathManager: ComposeClasspathManager? = null
    private var compiler: ComposeCompiler? = null
    private var compilerDaemon: CompilerDaemon? = null
    private var dexCompiler: ComposeDexCompiler? = null
    private var dexCache: DexCache? = null
    private var workDir: File? = null

    private var useDaemon = true
    private var daemonInitialized = false
    private val initializationDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    private var currentSource: String = ""
    private var projectClasspaths: List<File> = emptyList()
    private var runtimeDex: File? = null

    data class SourceUpdate(
        val source: String,
        val packageName: String,
        val className: String?,
        val previewConfigs: List<PreviewConfig>
    )

    init {
        viewModelScope.launch {
            sourceChanges
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged { old, new -> old.source == new.source }
                .collect { update ->
                    compileAndPreview(update)
                }
        }
    }

    fun initialize(context: Context, filePath: String) {
        if (classpathManager != null) return

        viewModelScope.launch {
            _previewState.value = PreviewState.Initializing

            val cacheDir = context.cacheDir
            workDir = File(cacheDir, "compose_preview_work").apply {
                mkdirs()
            }

            val dexCacheDir = File(cacheDir, "compose_dex_cache")
            dexCache = DexCache(dexCacheDir)

            classpathManager = ComposeClasspathManager(context)
            compiler = ComposeCompiler(classpathManager!!, workDir!!)
            compilerDaemon = CompilerDaemon(classpathManager!!, workDir!!)
            dexCompiler = ComposeDexCompiler(classpathManager!!)

            val extracted = classpathManager!!.ensureComposeJarsExtracted()
            if (!extracted) {
                _previewState.value = PreviewState.Error(
                    "Failed to initialize Compose dependencies"
                )
                return@launch
            }

            runtimeDex = classpathManager!!.getOrCreateRuntimeDex()
            if (runtimeDex != null) {
                LOG.info("Compose runtime DEX ready: {}", runtimeDex!!.absolutePath)
            } else {
                LOG.warn("Failed to create Compose runtime DEX, preview may fail at runtime")
            }

            if (filePath.isNotBlank()) {
                try {
                    val file = File(filePath)
                    LOG.info("Looking for module for file: {}", file.absolutePath)
                    val projectManager = IProjectManager.getInstance()
                    val module = projectManager.findModuleForFile(file)
                    LOG.info("Found module: {} (type: {})", module?.name ?: "none", module?.javaClass?.simpleName ?: "null")
                    projectClasspaths = module?.getCompileClasspaths()?.toList() ?: emptyList()
                    LOG.info("Found {} project classpaths for module: {}", projectClasspaths.size, module?.name ?: "none")
                    if (projectClasspaths.isNotEmpty()) {
                        val existingCount = projectClasspaths.count { it.exists() }
                        LOG.info("  {} of {} classpaths exist", existingCount, projectClasspaths.size)
                        projectClasspaths.take(5).forEach { cp ->
                            LOG.debug("  Classpath: {} (exists: {})", cp.absolutePath, cp.exists())
                        }
                        if (projectClasspaths.size > 5) {
                            LOG.debug("  ... and {} more", projectClasspaths.size - 5)
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to get project classpaths", e)
                    projectClasspaths = emptyList()
                }
            }

            initializationDeferred.complete(Unit)
            _previewState.value = PreviewState.Idle
            LOG.info("ComposePreviewViewModel initialized, runtimeDex={}", runtimeDex?.absolutePath ?: "null")
        }
    }

    fun onSourceChanged(source: String) {
        currentSource = source

        val packageName = extractPackageName(source)
        if (packageName == null) {
            _previewState.value = PreviewState.Error("Missing package declaration in source")
            return
        }

        val previewConfigs = detectAllPreviewFunctions(source)
        if (previewConfigs.isEmpty()) {
            _previewState.value = PreviewState.Empty
            return
        }

        val functionNames = previewConfigs.map { it.functionName }
        _availablePreviews.value = functionNames
        if (_selectedPreview.value == null || !functionNames.contains(_selectedPreview.value)) {
            _selectedPreview.value = functionNames.first()
        }

        val className = extractClassName(source)

        viewModelScope.launch {
            sourceChanges.emit(
                SourceUpdate(source, packageName, className, previewConfigs)
            )
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
    }

    fun toggleDisplayMode() {
        _displayMode.value = when (_displayMode.value) {
            DisplayMode.ALL -> DisplayMode.SINGLE
            DisplayMode.SINGLE -> DisplayMode.ALL
        }
    }

    fun selectPreview(functionName: String) {
        if (_availablePreviews.value.contains(functionName)) {
            _selectedPreview.value = functionName
        }
    }

    fun compileNow(source: String) {
        currentSource = source

        val packageName = extractPackageName(source)
        if (packageName == null) {
            _previewState.value = PreviewState.Error("Missing package declaration in source")
            return
        }

        val previewConfigs = detectAllPreviewFunctions(source)
        if (previewConfigs.isEmpty()) {
            _previewState.value = PreviewState.Empty
            return
        }

        val functionNames = previewConfigs.map { it.functionName }
        _availablePreviews.value = functionNames
        if (_selectedPreview.value == null || !functionNames.contains(_selectedPreview.value)) {
            _selectedPreview.value = functionNames.first()
        }

        val className = extractClassName(source)

        viewModelScope.launch {
            compileAndPreview(SourceUpdate(source, packageName, className, previewConfigs))
        }
    }

    private fun extractPackageName(source: String): String? {
        val packagePattern = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        return packagePattern.find(source)?.groupValues?.get(1)
    }

    private fun extractClassName(source: String): String? {
        val classPattern = Regex("""^\s*class\s+(\w+)""", RegexOption.MULTILINE)
        classPattern.find(source)?.groupValues?.get(1)?.let { return it }

        val objectPattern = Regex("""^\s*object\s+(\w+)""", RegexOption.MULTILINE)
        objectPattern.find(source)?.groupValues?.get(1)?.let { return it }

        return null
    }

    private fun detectAllPreviewFunctions(source: String): List<PreviewConfig> {
        val previews = mutableListOf<PreviewConfig>()
        val seenFunctions = mutableSetOf<String>()

        val previewPattern = Regex(
            """@Preview\s*(?:\(([^)]*)\))?\s*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*fun\s+(\w+)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
        previewPattern.findAll(source).forEach { match ->
            val params = match.groupValues[1]
            val functionName = match.groupValues[2]
            if (seenFunctions.add(functionName)) {
                previews.add(PreviewConfig(
                    functionName = functionName,
                    heightDp = extractIntParam(params, "heightDp"),
                    widthDp = extractIntParam(params, "widthDp")
                ))
            }
        }

        val composablePreviewPattern = Regex(
            """@Composable\s*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*@Preview\s*(?:\(([^)]*)\))?[\s\n]*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*fun\s+(\w+)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
        composablePreviewPattern.findAll(source).forEach { match ->
            val params = match.groupValues[1]
            val functionName = match.groupValues[2]
            if (seenFunctions.add(functionName)) {
                previews.add(PreviewConfig(
                    functionName = functionName,
                    heightDp = extractIntParam(params, "heightDp"),
                    widthDp = extractIntParam(params, "widthDp")
                ))
            }
        }

        if (previews.isEmpty()) {
            val composablePattern = Regex("""@Composable\s+fun\s+(\w+)""")
            composablePattern.findAll(source).forEach { match ->
                val functionName = match.groupValues[1]
                if (seenFunctions.add(functionName)) {
                    previews.add(PreviewConfig(functionName = functionName))
                }
            }
        }

        LOG.debug("Detected {} preview functions: {}", previews.size, previews.map { it.functionName })
        return previews
    }

    private fun extractIntParam(params: String, name: String): Int? {
        if (params.isBlank()) return null
        val pattern = Regex("""$name\s*=\s*(\d+)""")
        return pattern.find(params)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun compileAndPreview(update: SourceUpdate) {
        initializationDeferred.await()

        val cache = dexCache ?: run {
            _previewState.value = PreviewState.Error("Preview not initialized")
            return
        }

        val fileName = update.className?.removeSuffix("Kt") ?: "Preview"
        val generatedClassName = "${fileName}Kt"
        val fullClassName = "${update.packageName}.$generatedClassName"

        val sourceHash = cache.computeSourceHash(update.source)

        val cached = cache.getCachedDex(sourceHash)
        if (cached != null) {
            LOG.info("Using cached DEX for hash: {}, runtimeDex={}", sourceHash, runtimeDex?.absolutePath ?: "null")
            _previewState.value = PreviewState.Ready(
                dexFile = cached.dexFile,
                className = fullClassName,
                previewConfigs = update.previewConfigs,
                runtimeDex = runtimeDex
            )
            return
        }

        val compiler = this.compiler
        val compilerDaemon = this.compilerDaemon
        val dexCompiler = this.dexCompiler
        val workDir = this.workDir
        val classpathManager = this.classpathManager

        if (compiler == null || dexCompiler == null || workDir == null || classpathManager == null) {
            _previewState.value = PreviewState.Error("Preview not initialized")
            return
        }

        _previewState.value = PreviewState.Compiling

        val sourceDir = File(workDir, "src").apply {
            deleteRecursively()
            mkdirs()
        }

        val packageDir = File(sourceDir, update.packageName.replace('.', '/'))
        packageDir.mkdirs()

        val sourceFile = File(packageDir, "$fileName.kt")
        val transformedSource = PreviewSourceTransformer.transform(update.source)
        LOG.info("=== Transformed Source ===\n{}\n=== End Source ===", transformedSource)
        sourceFile.writeText(transformedSource)

        val classesDir = File(workDir, "classes").apply {
            deleteRecursively()
            mkdirs()
        }

        LOG.debug("Compiling source: {}", sourceFile.absolutePath)
        LOG.info("Using {} project classpaths for compilation", projectClasspaths.size)

        val compilationSuccess: Boolean
        val compilationError: String
        var compilationDiagnostics: List<CompileDiagnostic> = emptyList()

        val classpath = classpathManager.getCompilationClasspath(projectClasspaths)

        if (useDaemon && compilerDaemon != null) {
            val daemonResult = try {
                compilerDaemon.compile(
                    sourceFiles = listOf(sourceFile),
                    outputDir = classesDir,
                    classpath = classpath,
                    composePlugin = classpathManager.getCompilerPlugin()
                )
            } catch (e: Exception) {
                LOG.warn("Daemon compilation failed, falling back to regular compiler", e)
                useDaemon = false
                null
            }

            if (daemonResult != null) {
                compilationSuccess = daemonResult.success
                compilationError = daemonResult.errorOutput.ifEmpty { daemonResult.output }
                if (!daemonInitialized && daemonResult.success) {
                    daemonInitialized = true
                    LOG.info("Daemon initialized successfully, subsequent compilations will be faster")
                }
            } else {
                val result = compiler.compile(listOf(sourceFile), classesDir, projectClasspaths)
                compilationSuccess = result.success
                compilationDiagnostics = result.diagnostics
                compilationError = result.errorOutput.ifEmpty {
                    result.diagnostics
                        .filter { it.severity == CompileDiagnostic.Severity.ERROR }
                        .joinToString("\n") { it.message }
                }
            }
        } else {
            val result = compiler.compile(listOf(sourceFile), classesDir, projectClasspaths)
            compilationSuccess = result.success
            compilationDiagnostics = result.diagnostics
            compilationError = result.errorOutput.ifEmpty {
                result.diagnostics
                    .filter { it.severity == CompileDiagnostic.Severity.ERROR }
                    .joinToString("\n") { it.message }
            }
        }

        if (!compilationSuccess) {
            LOG.error("Compilation failed: {}", compilationError)
            _previewState.value = PreviewState.Error(
                message = compilationError.ifEmpty { "Compilation failed" },
                diagnostics = compilationDiagnostics
            )
            return
        }

        val dexDir = File(workDir, "dex").apply {
            deleteRecursively()
            mkdirs()
        }

        LOG.debug("Converting to DEX")

        val dexResult = dexCompiler.compileToDex(classesDir, dexDir)

        if (!dexResult.success || dexResult.dexFile == null) {
            LOG.error("DEX compilation failed: {}", dexResult.errorMessage)
            _previewState.value = PreviewState.Error(
                message = dexResult.errorMessage.ifEmpty { "DEX compilation failed" }
            )
            return
        }

        cache.cacheDex(sourceHash, dexResult.dexFile, fullClassName, update.previewConfigs.firstOrNull()?.functionName ?: "")

        _previewState.value = PreviewState.Ready(
            dexFile = dexResult.dexFile,
            className = fullClassName,
            previewConfigs = update.previewConfigs,
            runtimeDex = runtimeDex
        )

        LOG.info("Preview ready: {} with {} previews", fullClassName, update.previewConfigs.size)
    }

    override fun onCleared() {
        super.onCleared()
        compilerDaemon?.stopDaemon()
        LOG.debug("ComposePreviewViewModel cleared")
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewViewModel::class.java)
        private const val DEBOUNCE_MS = 500L
    }
}
