package com.itsaky.androidide.compose.preview.compiler

import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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

            val d8Jar = findD8Jar()
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

                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                    .use { it.readText() }
                val stderr = BufferedReader(InputStreamReader(process.errorStream))
                    .use { it.readText() }

                val exitCode = process.waitFor()

                val dexFile = File(outputDir, "classes.dex")
                val success = exitCode == 0 && dexFile.exists()

                if (!success) {
                    LOG.error("D8 failed. Exit: {}, stderr: {}", exitCode, stderr)
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

    private fun findD8Jar(): File? {
        val buildToolsVersions = listOf("35.0.0", "34.0.0", "33.0.2", "33.0.0")

        for (version in buildToolsVersions) {
            val d8Jar = File(Environment.ANDROID_HOME, "build-tools/$version/lib/d8.jar")
            if (d8Jar.exists()) {
                return d8Jar
            }
        }

        val buildToolsDir = File(Environment.ANDROID_HOME, "build-tools")
        if (buildToolsDir.exists()) {
            val latestVersion = buildToolsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }

            if (latestVersion != null) {
                val d8Jar = File(latestVersion, "lib/d8.jar")
                if (d8Jar.exists()) {
                    return d8Jar
                }
            }
        }

        return null
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
    }
}
