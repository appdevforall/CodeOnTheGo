package com.itsaky.androidide.compose.preview.data.repository

import android.content.Context
import com.itsaky.androidide.compose.preview.compiler.CompileDiagnostic
import com.itsaky.androidide.compose.preview.compiler.CompilerDaemon
import com.itsaky.androidide.compose.preview.compiler.ComposeClasspathManager
import com.itsaky.androidide.compose.preview.compiler.ComposeCompiler
import com.itsaky.androidide.compose.preview.compiler.ComposeDexCompiler
import com.itsaky.androidide.compose.preview.compiler.DexCache
import com.itsaky.androidide.compose.preview.compiler.PreviewSourceTransformer
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
    private var useDaemon = true
    private var daemonInitialized = false

    override suspend fun initialize(
        context: Context,
        filePath: String
    ): Result<InitializationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = context.cacheDir
            workDir = File(cacheDir, "compose_preview_work").apply { mkdirs() }

            val dexCacheDir = File(cacheDir, "compose_dex_cache")
            dexCache = DexCache(dexCacheDir)

            classpathManager = ComposeClasspathManager(context)
            compiler = ComposeCompiler(classpathManager!!, workDir!!)
            compilerDaemon = CompilerDaemon(classpathManager!!, workDir!!)
            dexCompiler = ComposeDexCompiler(classpathManager!!)

            val extracted = classpathManager!!.ensureComposeJarsExtracted()
            if (!extracted) {
                return@runCatching InitializationResult.Failed(
                    "Failed to initialize Compose dependencies"
                )
            }

            runtimeDex = classpathManager!!.getOrCreateRuntimeDex()
            if (runtimeDex != null) {
                LOG.info("Compose runtime DEX ready: {}", runtimeDex!!.absolutePath)
            } else {
                LOG.warn("Failed to create Compose runtime DEX, preview may fail at runtime")
            }

            projectContext = projectContextSource.resolveContext(filePath)

            if (projectContext!!.needsBuild && projectContext!!.modulePath != null) {
                LOG.warn("No intermediate classes found - build the project to enable multi-file previews")
                InitializationResult.NeedsBuild(
                    projectContext!!.modulePath!!,
                    projectContext!!.variantName
                )
            } else {
                LOG.info("Repository initialized, runtimeDex={}", runtimeDex?.absolutePath ?: "null")
                InitializationResult.Ready(runtimeDex, projectContext!!)
            }
        }
    }

    override suspend fun compilePreview(
        source: String,
        parsedSource: ParsedPreviewSource
    ): Result<CompilationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = dexCache ?: throw IllegalStateException("Repository not initialized")
            val compiler = this@ComposePreviewRepositoryImpl.compiler
                ?: throw IllegalStateException("Repository not initialized")
            val compilerDaemon = this@ComposePreviewRepositoryImpl.compilerDaemon
            val dexCompiler = this@ComposePreviewRepositoryImpl.dexCompiler
                ?: throw IllegalStateException("Repository not initialized")
            val workDir = this@ComposePreviewRepositoryImpl.workDir
                ?: throw IllegalStateException("Repository not initialized")
            val classpathManager = this@ComposePreviewRepositoryImpl.classpathManager
                ?: throw IllegalStateException("Repository not initialized")
            val context = projectContext ?: throw IllegalStateException("Repository not initialized")

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
            val transformedSource = PreviewSourceTransformer.transform(source)
            LOG.info("=== Transformed Source ===\n{}\n=== End Source ===", transformedSource)
            sourceFile.writeText(transformedSource)

            val classesDir = File(workDir, "classes").apply {
                deleteRecursively()
                mkdirs()
            }

            LOG.debug("Compiling source: {}", sourceFile.absolutePath)
            LOG.info("Using {} project classpaths for compilation", context.compileClasspaths.size)

            val compilationSuccess: Boolean
            val compilationError: String
            var compilationDiagnostics: List<CompileDiagnostic> = emptyList()

            val classpath = classpathManager.getCompilationClasspath(context.compileClasspaths)

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
                    val result = compiler.compile(listOf(sourceFile), classesDir, context.compileClasspaths)
                    compilationSuccess = result.success
                    compilationDiagnostics = result.diagnostics
                    compilationError = result.errorOutput.ifEmpty {
                        result.diagnostics
                            .filter { it.severity == CompileDiagnostic.Severity.ERROR }
                            .joinToString("\n") { it.message }
                    }
                }
            } else {
                val result = compiler.compile(listOf(sourceFile), classesDir, context.compileClasspaths)
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
                throw CompilationException(
                    message = compilationError.ifEmpty { "Compilation failed" },
                    diagnostics = compilationDiagnostics
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

            cache.cacheDex(
                sourceHash,
                dexResult.dexFile,
                fullClassName,
                parsedSource.previewConfigs.firstOrNull()?.functionName ?: ""
            )

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
        return dexCache?.computeSourceHash(source) ?: source.hashCode().toString()
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

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposePreviewRepositoryImpl::class.java)
    }
}
