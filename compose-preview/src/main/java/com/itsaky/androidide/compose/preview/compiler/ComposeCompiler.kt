package com.itsaky.androidide.compose.preview.compiler

import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class CompilationResult(
    val success: Boolean,
    val outputDir: File?,
    val diagnostics: List<CompileDiagnostic>,
    val errorOutput: String = ""
)

data class CompileDiagnostic(
    val severity: Severity,
    val message: String,
    val file: String?,
    val line: Int?,
    val column: Int?
) {
    enum class Severity { ERROR, WARNING, INFO }
}

class ComposeCompiler(
    private val classpathManager: ComposeClasspathManager,
    private val workDir: File
) {
    private val incrementalCacheDir = File(workDir, "ic-cache").apply { mkdirs() }

    suspend fun compile(
        sourceFiles: List<File>,
        outputDir: File,
        additionalClasspaths: List<File> = emptyList()
    ): CompilationResult =
        withContext(Dispatchers.IO) {
            outputDir.mkdirs()

            val classpath = classpathManager.getCompilationClasspath(additionalClasspaths)
            val kotlinCompiler = classpathManager.getKotlinCompiler()
            val composePlugin = classpathManager.getCompilerPlugin()
            val compilerBootstrapClasspath = classpathManager.getCompilerBootstrapClasspath()

            if (!kotlinCompiler.exists()) {
                return@withContext CompilationResult(
                    success = false,
                    outputDir = null,
                    diagnostics = listOf(
                        CompileDiagnostic(
                            CompileDiagnostic.Severity.ERROR,
                            "Kotlin compiler not found at ${kotlinCompiler.absolutePath}",
                            null, null, null
                        )
                    )
                )
            }

            val args = buildCompilerArgs(
                sourceFiles = sourceFiles,
                outputDir = outputDir,
                classpath = classpath,
                composePlugin = composePlugin
            )

            LOG.info("Compiling with args: {}", args.joinToString(" "))

            try {
                val result = invokeKotlinCompiler(compilerBootstrapClasspath, args)
                parseCompilationResult(result, outputDir)
            } catch (e: Exception) {
                LOG.error("Compilation failed", e)
                CompilationResult(
                    success = false,
                    outputDir = null,
                    diagnostics = listOf(
                        CompileDiagnostic(
                            CompileDiagnostic.Severity.ERROR,
                            "Compilation exception: ${e.message}",
                            null, null, null
                        )
                    ),
                    errorOutput = e.stackTraceToString()
                )
            }
        }

    private fun buildCompilerArgs(
        sourceFiles: List<File>,
        outputDir: File,
        classpath: String,
        composePlugin: File
    ): List<String> = buildList {
        if (composePlugin.exists()) {
            LOG.info("Using Compose compiler plugin: {}", composePlugin.absolutePath)
            add("-Xplugin=${composePlugin.absolutePath}")
        } else {
            LOG.warn("Compose compiler plugin NOT found at: {}", composePlugin.absolutePath)
        }

        add("-classpath")
        add(classpath)

        add("-d")
        add(outputDir.absolutePath)

        add("-jvm-target")
        add("1.8")

        add("-no-stdlib")

        add("-Xskip-metadata-version-check")

        sourceFiles.forEach { file ->
            add(file.absolutePath)
        }
    }

    private fun invokeKotlinCompiler(
        compilerBootstrapClasspath: String,
        args: List<String>
    ): ProcessResult {
        val javaExecutable = Environment.JAVA

        if (!javaExecutable.exists()) {
            LOG.error("Java executable not found at: {}", javaExecutable.absolutePath)
            return ProcessResult(-1, "", "Java executable not found at: ${javaExecutable.absolutePath}")
        }

        if (compilerBootstrapClasspath.isEmpty()) {
            LOG.error("Compiler bootstrap classpath is empty")
            return ProcessResult(-1, "", "Compiler bootstrap classpath is empty")
        }

        val command = buildList {
            add(javaExecutable.absolutePath)
            add("-cp")
            add(compilerBootstrapClasspath)
            add("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            addAll(args)
        }

        LOG.debug("Running: {}", command.joinToString(" "))

        val processBuilder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(false)

        val process = processBuilder.start()

        val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }

        val exitCode = process.waitFor()

        return ProcessResult(exitCode, stdout, stderr)
    }

    private fun parseCompilationResult(
        processResult: ProcessResult,
        outputDir: File
    ): CompilationResult {
        val diagnostics = mutableListOf<CompileDiagnostic>()

        val combinedOutput = processResult.stderr + processResult.stdout
        val diagnosticRegex = Regex("""(.+):(\d+):(\d+): (error|warning): (.+)""")

        combinedOutput.lines().forEach { line ->
            val match = diagnosticRegex.find(line)
            if (match != null) {
                val (file, lineNum, col, severity, message) = match.destructured
                diagnostics.add(
                    CompileDiagnostic(
                        severity = when (severity) {
                            "error" -> CompileDiagnostic.Severity.ERROR
                            "warning" -> CompileDiagnostic.Severity.WARNING
                            else -> CompileDiagnostic.Severity.INFO
                        },
                        message = message,
                        file = file,
                        line = lineNum.toIntOrNull(),
                        column = col.toIntOrNull()
                    )
                )
            } else if (line.contains("error:", ignoreCase = true)) {
                diagnostics.add(
                    CompileDiagnostic(
                        CompileDiagnostic.Severity.ERROR,
                        line,
                        null, null, null
                    )
                )
            }
        }

        val hasErrors = diagnostics.any { it.severity == CompileDiagnostic.Severity.ERROR }
        val hasClassFiles = outputDir.walkTopDown().any { it.extension == "class" }
        val success = processResult.exitCode == 0 && !hasErrors && hasClassFiles

        return CompilationResult(
            success = success,
            outputDir = if (success) outputDir else null,
            diagnostics = diagnostics,
            errorOutput = if (!success) processResult.stderr else ""
        )
    }

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposeCompiler::class.java)
    }
}
