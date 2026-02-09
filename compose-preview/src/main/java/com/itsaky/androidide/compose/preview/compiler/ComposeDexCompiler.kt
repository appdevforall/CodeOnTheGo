package com.itsaky.androidide.compose.preview.compiler

import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class DexCompilationResult(
    val success: Boolean,
    val dexFile: File?,
    val errorMessage: String = ""
)

class ComposeDexCompiler(
    private val classpathManager: ComposeClasspathManager
) {

    suspend fun compileToDex(classesDir: File, outputDir: File): DexCompilationResult =
        withContext(Dispatchers.IO) {
            outputDir.mkdirs()

            val d8Jar = classpathManager.getD8Jar()
            if (d8Jar == null || !d8Jar.exists()) {
                return@withContext DexCompilationResult(
                    success = false,
                    dexFile = null,
                    errorMessage = "D8 jar not found"
                )
            }

            val javaExecutable = Environment.JAVA
            if (!javaExecutable.exists()) {
                return@withContext DexCompilationResult(
                    success = false,
                    dexFile = null,
                    errorMessage = "Java executable not found"
                )
            }

            val classFiles = classesDir.walkTopDown()
                .filter { it.extension == "class" }
                .toList()

            if (classFiles.isEmpty()) {
                return@withContext DexCompilationResult(
                    success = false,
                    dexFile = null,
                    errorMessage = "No .class files found in $classesDir"
                )
            }

            val command = buildD8Command(javaExecutable, d8Jar, classFiles, outputDir)

            LOG.info("Running D8: {}", command.joinToString(" "))

            try {
                val processBuilder = ProcessBuilder(command)
                    .directory(classesDir)
                    .redirectErrorStream(false)

                val process = processBuilder.start()

                val stdoutDeferred = async {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                }
                val stderrDeferred = async {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                }

                val completed = process.waitFor(DEX_TIMEOUT_MINUTES, TimeUnit.MINUTES)

                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()

                if (!completed) {
                    process.destroyForcibly()
                    LOG.error("D8 timed out after {} minutes. stdout: {}, stderr: {}", DEX_TIMEOUT_MINUTES, stdout, stderr)
                    return@withContext DexCompilationResult(
                        success = false,
                        dexFile = null,
                        errorMessage = "D8 timed out after $DEX_TIMEOUT_MINUTES minutes"
                    )
                }

                val dexFile = File(outputDir, "classes.dex")
                val success = process.exitValue() == 0 && dexFile.exists()

                if (!success) {
                    LOG.error("D8 failed. Exit: {}, stderr: {}", process.exitValue(), stderr)
                }

                DexCompilationResult(
                    success = success,
                    dexFile = if (success) dexFile else null,
                    errorMessage = if (!success) stderr.ifEmpty { stdout } else ""
                )
            } catch (e: Exception) {
                LOG.error("D8 execution failed", e)
                DexCompilationResult(
                    success = false,
                    dexFile = null,
                    errorMessage = "D8 execution failed: ${e.message}"
                )
            }
        }

    private fun buildD8Command(
        javaExecutable: File,
        d8Jar: File,
        classFiles: List<File>,
        outputDir: File
    ): List<String> = buildList {
        add(javaExecutable.absolutePath)
        add("-cp")
        add(d8Jar.absolutePath)
        add("com.android.tools.r8.D8")
        add("--release")
        add("--min-api")
        add("21")

        classpathManager.getRuntimeJars()
            .filter { it.exists() }
            .forEach { jar ->
                add("--classpath")
                add(jar.absolutePath)
            }

        if (Environment.ANDROID_JAR.exists()) {
            add("--lib")
            add(Environment.ANDROID_JAR.absolutePath)
        }

        add("--output")
        add(outputDir.absolutePath)

        classFiles.forEach { classFile ->
            add(classFile.absolutePath)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposeDexCompiler::class.java)
        private const val DEX_TIMEOUT_MINUTES = 5L
    }
}
