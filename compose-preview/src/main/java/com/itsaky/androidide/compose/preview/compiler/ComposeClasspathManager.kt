package com.itsaky.androidide.compose.preview.compiler

import android.content.Context
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipInputStream

class ComposeClasspathManager(private val context: Context) {

    private val composeDir: File
        get() = Environment.COMPOSE_HOME

    private val compilerBootstrapJars = listOf(
        "kotlin-compiler.jar",
        "kotlin-stdlib.jar",
        "kotlin-reflect.jar",
        "kotlin-script-runtime.jar",
        "trove4j.jar",
        "jetbrains-annotations.jar"
    )

    private val requiredCoreJars = listOf(
        "kotlin-compiler.jar",
        "kotlin-stdlib.jar",
        "compose-compiler-plugin.jar",
        "activity-1.8.2.jar",
        "foundation-layout-release.jar",
        "ui-unit-release.jar",
        "lifecycle-viewmodel-2.6.1.jar",
        "jetbrains-annotations.jar"
    )

    fun ensureComposeJarsExtracted(): Boolean {
        val extracted = areJarsExtracted()
        LOG.info("Compose JARs extracted: {}, dir exists: {}, dir: {}", extracted, composeDir.exists(), composeDir.absolutePath)

        if (extracted) {
            LOG.debug("Compose JARs already extracted")
            return true
        }

        return try {
            composeDir.deleteRecursively()
            extractComposeJars()
            true
        } catch (e: Exception) {
            LOG.error("Failed to extract Compose JARs", e)
            false
        }
    }

    private fun areJarsExtracted(): Boolean {
        val allExist = requiredCoreJars.all { jar ->
            val exists = File(composeDir, jar).exists()
            if (!exists) {
                LOG.debug("Missing JAR: {}", jar)
            }
            exists
        }
        return composeDir.exists() && allExist
    }

    private fun extractComposeJars() {
        composeDir.mkdirs()

        context.assets.open("compose/compose-jars.zip").use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val file = File(composeDir, entry.name)
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        LOG.info("Extracted Compose JARs to {}", composeDir)
    }

    fun getKotlinCompiler(): File {
        return File(composeDir, "kotlin-compiler.jar")
    }

    fun getCompilerPlugin(): File {
        return File(composeDir, "compose-compiler-plugin.jar")
    }

    fun getKotlinStdlib(): File {
        return File(composeDir, "kotlin-stdlib.jar")
    }

    fun getCompilerBootstrapClasspath(): String {
        return compilerBootstrapJars
            .map { File(composeDir, it) }
            .filter { it.exists() }
            .joinToString(File.pathSeparator) { it.absolutePath }
    }

    fun getRuntimeJars(): List<File> {
        return composeDir.listFiles { file ->
            file.extension == "jar" && !compilerBootstrapJars.contains(file.name)
        }?.toList() ?: emptyList()
    }

    fun getAllJars(): List<File> {
        return composeDir.listFiles { file ->
            file.extension == "jar"
        }?.toList() ?: emptyList()
    }

    fun getFullClasspath(): List<File> {
        return buildList {
            add(Environment.ANDROID_JAR)
            addAll(getAllJars())
        }
    }

    fun getCompilationClasspath(additionalJars: List<File> = emptyList()): String {
        val base = getFullClasspath()
        val extra = additionalJars.filter { it.exists() }
        val missingExtra = additionalJars.filter { !it.exists() }
        val all = (base + extra).filter { it.exists() }
        val classpath = all.joinToString(File.pathSeparator) { it.absolutePath }
        LOG.info("Compilation classpath has {} JARs ({} bundled, {} project, {} missing)", all.size, base.count { it.exists() }, extra.size, missingExtra.size)
        if (missingExtra.isNotEmpty()) {
            LOG.warn("Missing project classpaths (build the project first):")
            missingExtra.take(5).forEach { LOG.warn("  {}", it.absolutePath) }
        }
        return classpath
    }

    fun hasProjectClasspaths(additionalJars: List<File>): Boolean {
        return additionalJars.any { it.exists() }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposeClasspathManager::class.java)
    }
}
