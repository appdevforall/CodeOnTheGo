package com.itsaky.androidide.compose.preview.data.repository

import android.content.Context
import com.itsaky.androidide.compose.preview.compiler.CompileDiagnostic
import com.itsaky.androidide.compose.preview.compiler.CompilerDaemon
import com.itsaky.androidide.compose.preview.compiler.ComposeClasspathManager
import com.itsaky.androidide.compose.preview.compiler.ComposeCompiler
import com.itsaky.androidide.compose.preview.compiler.ComposeDexCompiler
import com.itsaky.androidide.compose.preview.compiler.DexCache
import com.itsaky.androidide.compose.preview.data.source.ProjectContext
import com.itsaky.androidide.compose.preview.data.source.ProjectContextSource
import com.itsaky.androidide.compose.preview.domain.model.ParsedPreviewSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

class ComposePreviewRepositoryImpl(
    private val projectContextSource: ProjectContextSource = ProjectContextSource()
) : ComposePreviewRepository {

    private var classpathManager: ComposeClasspathManager? = null
    private var compiler: ComposeCompiler? = null
    private var compilerDaemon: CompilerDaemon? = null
    private var dexCompiler: ComposeDexCompiler? = null
    private var dexCache: DexCache? = null
    private var workDir: File? = null

    private var runtimeDex: File? = null
    private var projectContext: ProjectContext? = null
    private var daemonInitialized = false

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewRepositoryImpl::class.java)
    }

    override suspend fun initialize(
        context: Context,
        filePath: String
    ): Result<InitializationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = context.cacheDir
            workDir = File(cacheDir, "compose_preview_work").apply { mkdirs() }

            val dexCacheDir = File(cacheDir, "compose_dex_cache")
            dexCache = DexCache(dexCacheDir)

            val cpManager = ComposeClasspathManager(context)
            val work = workDir ?: return@runCatching InitializationResult.Failed("Work directory not initialized")

            classpathManager = cpManager
            compiler = ComposeCompiler(cpManager, work)
            compilerDaemon = CompilerDaemon(cpManager, work)
            dexCompiler = ComposeDexCompiler(cpManager)

            val extracted = cpManager.ensureComposeJarsExtracted()
            if (!extracted) {
                return@runCatching InitializationResult.Failed(
                    "Failed to initialize Compose dependencies"
                )
            }

            runtimeDex = cpManager.getOrCreateRuntimeDex()
            if (runtimeDex != null) {
                LOG.info("Compose runtime DEX ready: {}", runtimeDex?.absolutePath)
            } else {
                LOG.warn("Failed to create Compose runtime DEX, preview may fail at runtime")
            }

            val ctx = projectContextSource.resolveContext(filePath)
            projectContext = ctx

            if (ctx.needsBuild && ctx.modulePath != null) {
                LOG.warn("No intermediate classes found - build the project to enable multi-file previews")
                InitializationResult.NeedsBuild(
                    ctx.modulePath,
                    ctx.variantName
                )
            } else {
                LOG.info("Repository initialized, runtimeDex={}", runtimeDex?.absolutePath ?: "null")
                InitializationResult.Ready(runtimeDex, ctx)
            }
        }
    }

    private fun <T> requireInitialized(value: T?, name: String): T {
        return value ?: throw IllegalStateException("Repository not initialized: $name is null. Call initialize() first.")
    }

    private data class SourceCompileResult(
        val success: Boolean,
        val error: String,
        val diagnostics: List<CompileDiagnostic> = emptyList()
    )

    override suspend fun compilePreview(
        source: String,
        parsedSource: ParsedPreviewSource
    ): Result<CompilationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = requireInitialized(dexCache, "dexCache")
            val compiler = requireInitialized(this@ComposePreviewRepositoryImpl.compiler, "compiler")
            val compilerDaemon = this@ComposePreviewRepositoryImpl.compilerDaemon
            val dexCompiler = requireInitialized(this@ComposePreviewRepositoryImpl.dexCompiler, "dexCompiler")
            val workDir = requireInitialized(this@ComposePreviewRepositoryImpl.workDir, "workDir")
            val classpathManager = requireInitialized(this@ComposePreviewRepositoryImpl.classpathManager, "classpathManager")
            val context = requireInitialized(projectContext, "projectContext")

            val fileName = parsedSource.className?.removeSuffix("Kt") ?: "Preview"
            val generatedClassName = "${fileName}Kt"
            val fullClassName = "${parsedSource.packageName}.$generatedClassName"

            val sourceHash = cache.computeSourceHash(source)

            val cached = cache.getCachedDex(sourceHash)
            if (cached != null) {
                LOG.info("Using cached DEX for hash: {}, runtimeDex={}, projectDexFiles={}",
                    sourceHash, runtimeDex?.absolutePath ?: "null", context.projectDexFiles.size)
                return@runCatching CompilationResult(
                    dexFile = cached.dexFile,
                    className = fullClassName,
                    runtimeDex = runtimeDex,
                    projectDexFiles = context.projectDexFiles
                )
            }

            val sourceDir = File(workDir, "src").apply {
                deleteRecursively()
                mkdirs()
            }

            val packageDir = File(sourceDir, parsedSource.packageName.replace('.', '/'))
            packageDir.mkdirs()

            val sourceFile = File(packageDir, "$fileName.kt")
            sourceFile.writeText(source)

            val classesDir = File(workDir, "classes").apply {
                deleteRecursively()
                mkdirs()
            }

            LOG.debug("Compiling source: {}", sourceFile.absolutePath)
            LOG.info("Using {} project classpaths for compilation", context.compileClasspaths.size)

            val classpath = classpathManager.getCompilationClasspath(context.compileClasspaths)

            var compileResult: SourceCompileResult? = null

            if (compilerDaemon != null) {
                val daemonResult = try {
                    compilerDaemon.compile(
                        sourceFiles = listOf(sourceFile),
                        outputDir = classesDir,
                        classpath = classpath,
                        composePlugin = classpathManager.getCompilerPlugin()
                    )
                } catch (e: Exception) {
                    LOG.warn("Daemon compilation failed, falling back to regular compiler", e)
                    null
                }

                if (daemonResult != null) {
                    if (daemonResult.success && !daemonInitialized) {
                        daemonInitialized = true
                        LOG.info("Daemon initialized successfully")
                    }
                    compileResult = SourceCompileResult(
                        success = daemonResult.success,
                        error = daemonResult.errorOutput.ifEmpty { daemonResult.output }
                    )
                }
            }

            if (compileResult == null) {
                val result = compiler.compile(listOf(sourceFile), classesDir, context.compileClasspaths)
                compileResult = SourceCompileResult(
                    success = result.success,
                    error = result.errorOutput.ifEmpty {
                        result.diagnostics
                            .filter { it.severity == CompileDiagnostic.Severity.ERROR }
                            .joinToString("\n") { it.message }
                    },
                    diagnostics = result.diagnostics
                )
            }

            if (!compileResult.success) {
                LOG.error("Compilation failed: {}", compileResult.error)
                throw CompilationException(
                    message = compileResult.error.ifEmpty { "Compilation failed" },
                    diagnostics = compileResult.diagnostics
                )
            }

            val dexDir = File(workDir, "dex").apply {
                deleteRecursively()
                mkdirs()
            }

            LOG.debug("Converting to DEX")

            val dexResult = dexCompiler.compileToDex(classesDir, dexDir)

            if (!dexResult.success || dexResult.dexFile == null) {
                LOG.error("DEX compilation failed: {}", dexResult.errorMessage)
                throw CompilationException(
                    message = dexResult.errorMessage.ifEmpty { "DEX compilation failed" }
                )
            }

            try {
                cache.cacheDex(
                    sourceHash,
                    dexResult.dexFile,
                    fullClassName,
                    parsedSource.previewConfigs.firstOrNull()?.functionName ?: ""
                )
            } catch (e: Exception) {
                LOG.warn("Failed to cache DEX file (non-fatal): {}", e.message)
            }

            LOG.info("Preview ready: {} with {} previews, {} project DEX files",
                fullClassName, parsedSource.previewConfigs.size, context.projectDexFiles.size)

            CompilationResult(
                dexFile = dexResult.dexFile,
                className = fullClassName,
                runtimeDex = runtimeDex,
                projectDexFiles = context.projectDexFiles
            )
        }
    }

    override fun computeSourceHash(source: String): String {
        val cache = dexCache
        if (cache == null) {
            LOG.warn("DexCache not initialized, using non-deterministic hash fallback")
            return source.hashCode().toString()
        }
        return cache.computeSourceHash(source)
    }

    override fun reset() {
        compilerDaemon?.stopDaemon()
        classpathManager = null
        compiler = null
        compilerDaemon = null
        dexCompiler = null
        daemonInitialized = false
        projectContext = null
        runtimeDex = null
        LOG.debug("Repository reset")
    }
}
