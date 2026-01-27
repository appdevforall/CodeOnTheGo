package com.itsaky.androidide.compose.preview.compiler

import android.content.Context
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class ComposeClasspathManager(private val context: Context) {

    private val composeDir: File
        get() = Environment.COMPOSE_HOME

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposeClasspathManager::class.java)

        private const val D8_HEAP_SIZE = "512m"
        private const val MIN_API_LEVEL = "21"
        private const val D8_TIMEOUT_MINUTES = 5L
    }

    private val runtimeDexDir: File
        get() = File(composeDir, "dex")

    private val localMavenRepo: File
        get() = File(Environment.HOME, "maven/localMvnRepository")

    private val dexMutex = Mutex()

    private val kotlinArtifacts = mapOf(
        "kotlin-compiler" to "org/jetbrains/kotlin/kotlin-compiler-embeddable",
        "kotlin-stdlib" to "org/jetbrains/kotlin/kotlin-stdlib",
        "kotlin-reflect" to "org/jetbrains/kotlin/kotlin-reflect",
        "kotlin-script-runtime" to "org/jetbrains/kotlin/kotlin-script-runtime",
        "trove4j" to "org/jetbrains/intellij/deps/trove4j",
        "annotations" to "org/jetbrains/annotations"
    )

    private val requiredRuntimeJarPatterns = listOf<Any>(
        "compose-compiler-plugin.jar",
        Regex("runtime-release\\.jar"),
        Regex("ui-release\\.jar"),
        Regex("animation-release\\.jar"),
        Regex("animation-core-release\\.jar"),
        Regex("foundation-release\\.jar"),
        Regex("material3-release\\.jar")
    )

    fun ensureComposeJarsExtracted(): Boolean {
        val extracted = areRuntimeJarsExtracted()
        LOG.info("Compose runtime JARs extracted: {}, dir: {}", extracted, composeDir.absolutePath)

        if (extracted) {
            LOG.debug("Compose runtime JARs already extracted")
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

    fun isKotlinCompilerAvailable(): Boolean {
        val compiler = findMavenJar("kotlin-compiler")
        val available = compiler?.exists() == true
        LOG.info("Kotlin compiler available in local Maven repo: {}", available)
        return available
    }

    private fun areRuntimeJarsExtracted(): Boolean {
        if (!composeDir.exists()) return false

        val files = composeDir.listFiles()?.map { it.name } ?: return false

        return requiredRuntimeJarPatterns.all { pattern ->
            when (pattern) {
                is String -> files.contains(pattern)
                is Regex -> files.any { pattern.matches(it) }
                else -> false
            }
        }
    }

    private fun findMavenJar(artifactKey: String): File? {
        val artifactPath = kotlinArtifacts[artifactKey] ?: return null
        val artifactDir = File(localMavenRepo, artifactPath)

        if (!artifactDir.exists()) {
            LOG.debug("Maven artifact dir not found: {}", artifactDir)
            return null
        }

        val versionDirs = artifactDir.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.name }
            ?: return null

        for (versionDir in versionDirs) {
            val jars = versionDir.listFiles { file ->
                file.extension == "jar" && !file.name.contains("-sources") && !file.name.contains("-javadoc")
            }
            if (!jars.isNullOrEmpty()) {
                LOG.debug("Found {} in local Maven repo: {}", artifactKey, jars[0])
                return jars[0]
            }
        }

        return null
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

    fun getKotlinCompiler(): File? {
        return findMavenJar("kotlin-compiler")
    }

    fun getCompilerPlugin(): File {
        return File(composeDir, "compose-compiler-plugin.jar")
    }

    fun getKotlinStdlib(): File? {
        return findMavenJar("kotlin-stdlib")
    }

    fun getCompilerBootstrapClasspath(): String {
        val jars = buildList {
            findMavenJar("kotlin-compiler")?.let { add(it) }
            findMavenJar("kotlin-stdlib")?.let { add(it) }
            findMavenJar("kotlin-reflect")?.let { add(it) }
            findMavenJar("kotlin-script-runtime")?.let { add(it) }
            findMavenJar("trove4j")?.let { add(it) }
            findMavenJar("annotations")?.let { add(it) }
        }
        return jars.filter { it.exists() }
            .joinToString(File.pathSeparator) { it.absolutePath }
    }

    fun getRuntimeJars(): List<File> {
        return composeDir.listFiles { file ->
            file.extension == "jar"
        }?.toList() ?: emptyList()
    }

    fun getAllJars(): List<File> {
        return buildList {
            addAll(getRuntimeJars())
            findMavenJar("kotlin-stdlib")?.let { add(it) }
        }
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
        return classpath
    }

    fun getD8Jar(): File? = findD8Jar()

    suspend fun getOrCreateRuntimeDex(): File? = dexMutex.withLock {
        withContext(Dispatchers.IO) {
            LOG.info("getOrCreateRuntimeDex called, runtimeDexDir={}", runtimeDexDir.absolutePath)
            val runtimeDex = File(runtimeDexDir, "compose-runtime.dex")

            if (runtimeDex.exists()) {
                LOG.info("Using cached Compose runtime DEX: {}", runtimeDex.absolutePath)
                return@withContext runtimeDex
            }

            LOG.info("Creating Compose runtime DEX (one-time operation)...")

            val runtimeJars = getRuntimeJars()
            if (runtimeJars.isEmpty()) {
                LOG.error("No runtime JARs found to dex")
                return@withContext null
            }

            val d8Jar = findD8Jar()
            if (d8Jar == null) {
                LOG.error("D8 jar not found")
                return@withContext null
            }

            val javaExecutable = Environment.JAVA
            if (!javaExecutable.exists()) {
                LOG.error("Java executable not found")
                return@withContext null
            }

            runtimeDexDir.mkdirs()

            val command = buildList {
                add(javaExecutable.absolutePath)
                add("-Xmx$D8_HEAP_SIZE")
                add("-cp")
                add(d8Jar.absolutePath)
                add("com.android.tools.r8.D8")
                add("--release")
                add("--min-api")
                add(MIN_API_LEVEL)
                add("--lib")
                add(Environment.ANDROID_JAR.absolutePath)
                add("--output")
                add(runtimeDexDir.absolutePath)
                runtimeJars.forEach { jar ->
                    add(jar.absolutePath)
                }
            }

            LOG.info("Running D8 for runtime JARs: {} JARs", runtimeJars.size)

            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val output = BufferedReader(InputStreamReader(process.inputStream))
                    .use { it.readText() }

                val completed = process.waitFor(D8_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                if (!completed) {
                    process.destroyForcibly()
                    LOG.error("D8 timed out after {} minutes. Output: {}", D8_TIMEOUT_MINUTES, output)
                    return@withContext null
                }

                val exitCode = process.exitValue()
                val outputDex = File(runtimeDexDir, "classes.dex")
                if (exitCode == 0 && outputDex.exists()) {
                    outputDex.renameTo(runtimeDex)
                    LOG.info("Compose runtime DEX created successfully")
                    return@withContext runtimeDex
                } else {
                    LOG.error("D8 failed for runtime. Exit: {}, output: {}", exitCode, output)
                    return@withContext null
                }
            } catch (e: Exception) {
                LOG.error("Failed to create runtime DEX", e)
                return@withContext null
            }
        }
    }

    private fun findD8Jar(): File? {
        val buildToolsDir = File(Environment.ANDROID_HOME, "build-tools")
        if (!buildToolsDir.exists()) {
            LOG.warn("Build tools directory not found: {}", buildToolsDir)
            return null
        }

        val installedVersions = buildToolsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (versionDir in installedVersions) {
            val d8Jar = File(versionDir, "lib/d8.jar")
            if (d8Jar.exists()) {
                LOG.debug("Using D8 from build-tools {}", versionDir.name)
                return d8Jar
            }
        }

        LOG.warn("D8 jar not found in any installed build-tools version")
        return null
    }

}
