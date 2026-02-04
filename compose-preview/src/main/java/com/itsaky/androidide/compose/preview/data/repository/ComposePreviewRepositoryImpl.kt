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
    private var cachedClasspath: String? = null

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewRepositoryImpl::class.java)
    }

    override suspend fun initialize(
        context: Context,
        filePath: String
    ): Result<InitializationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = projectContextSource.resolveContext(filePath)
            projectContext = ctx

            if (ctx.needsBuild && ctx.modulePath != null) {
                LOG.warn("No intermediate classes found - build required before initialization")
                return@runCatching InitializationResult.NeedsBuild(ctx.modulePath, ctx.variantName)
            }

            val cpManager = initializeInfrastructure(context)

            if (!cpManager.ensureComposeJarsExtracted()) {
                return@runCatching InitializationResult.Failed(
                    "Failed to initialize Compose dependencies"
                )
            }

            runtimeDex = cpManager.getOrCreateRuntimeDex()
            if (runtimeDex == null) {
                LOG.error("Failed to create Compose runtime DEX")
                return@runCatching InitializationResult.Failed(
                    "Failed to create Compose runtime. Check that Android SDK build-tools are installed."
                )
            }

            LOG.info("Compose runtime DEX ready: {}", runtimeDex?.absolutePath)

            try {
                compilerDaemon?.startEagerly()
                LOG.info("Compiler daemon pre-started")
            } catch (e: Exception) {
                LOG.warn("Failed to pre-start compiler daemon (non-fatal)", e)
            }

            LOG.info("Repository initialized, runtimeDex={}", runtimeDex?.absolutePath ?: "null")
            InitializationResult.Ready(runtimeDex, ctx)
        }
    }

    private fun initializeInfrastructure(context: Context): ComposeClasspathManager {
        val cacheDir = context.cacheDir
        val work = File(cacheDir, "compose_preview_work").apply { mkdirs() }
        workDir = work

        dexCache = DexCache(File(cacheDir, "compose_dex_cache"))

        val cpManager = ComposeClasspathManager(context)
        classpathManager = cpManager
        compiler = ComposeCompiler(cpManager, work)
        compilerDaemon = CompilerDaemon(cpManager, work)
        dexCompiler = ComposeDexCompiler(cpManager)
        return cpManager
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

            val sourceDir = File(workDir, "src")
            val packageDir = File(sourceDir, parsedSource.packageName.replace('.', '/'))
            packageDir.mkdirs()

            val sourceFile = File(packageDir, "$fileName.kt")
            sourceFile.writeText(source)

            val classesDir = File(workDir, "classes").apply { mkdirs() }

            LOG.debug("Compiling source: {}", sourceFile.absolutePath)
            LOG.info("Using {} project classpaths for compilation", context.compileClasspaths.size)

            val classpath = cachedClasspath
                ?: classpathManager.getCompilationClasspath(context.compileClasspaths).also {
                    cachedClasspath = it
                }

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

            val dexDir = File(workDir, "dex").apply { mkdirs() }

            LOG.debug("Converting to DEX")

            var dexFile: File? = null

            if (compilerDaemon != null) {
                val daemonDex = try {
                    compilerDaemon.dex(classesDir, dexDir)
                } catch (e: Exception) {
                    LOG.warn("Daemon D8 failed, falling back to subprocess", e)
                    null
                }

                if (daemonDex != null && daemonDex.success && daemonDex.dexFile != null) {
                    dexFile = daemonDex.dexFile
                }
            }

            if (dexFile == null) {
                val dexResult = dexCompiler.compileToDex(classesDir, dexDir)
                if (!dexResult.success || dexResult.dexFile == null) {
                    LOG.error("DEX compilation failed: {}", dexResult.errorMessage)
                    throw CompilationException(
                        message = dexResult.errorMessage.ifEmpty { "DEX compilation failed" }
                    )
                }
                dexFile = dexResult.dexFile
            }

            try {
                cache.cacheDex(
                    sourceHash,
                    dexFile,
                    fullClassName,
                    parsedSource.previewConfigs.firstOrNull()?.functionName ?: ""
                )
            } catch (e: Exception) {
                LOG.warn("Failed to cache DEX file (non-fatal): {}", e.message)
            }

            LOG.info("Preview ready: {} with {} previews, {} project DEX files",
                fullClassName, parsedSource.previewConfigs.size, context.projectDexFiles.size)

            CompilationResult(
                dexFile = dexFile,
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
        compilerDaemon?.shutdown()
        classpathManager = null
        compiler = null
        compilerDaemon = null
        dexCompiler = null
        daemonInitialized = false
        cachedClasspath = null
        projectContext = null
        runtimeDex = null
        LOG.debug("Repository reset")
    }
}
