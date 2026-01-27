package com.itsaky.androidide.compose.preview.runtime

import android.content.Context
import dalvik.system.DexClassLoader
import org.slf4j.LoggerFactory
import java.io.File

class ComposeClassLoader(private val context: Context) {

    private var currentLoader: DexClassLoader? = null
    private var currentCacheKey: String? = null
    private var runtimeDexFile: File? = null
    private var projectDexFiles: List<File> = emptyList()

    fun setRuntimeDex(runtimeDex: File?) {
        LOG.info("setRuntimeDex called: {} (current: {})",
            runtimeDex?.absolutePath ?: "null",
            runtimeDexFile?.absolutePath ?: "null")
        runtimeDexFile = runtimeDex
        release()
        LOG.info("Runtime DEX updated to: {}", runtimeDex?.absolutePath ?: "null")
    }

    fun setProjectDexFiles(dexFiles: List<File>) {
        val existingFiles = dexFiles.filter { it.exists() }
        LOG.info("setProjectDexFiles called: {} files ({} exist)",
            dexFiles.size, existingFiles.size)
        projectDexFiles = existingFiles
        release()
        existingFiles.forEach { LOG.info("  Project DEX: {}", it.absolutePath) }
    }

    fun loadClass(dexFile: File, className: String): Class<*>? {
        if (!dexFile.exists()) {
            LOG.error("DEX file not found: {}", dexFile.absolutePath)
            return null
        }

        return try {
            val loader = getOrCreateLoader(dexFile)
            loader.loadClass(className).also {
                LOG.debug("Loaded class: {}", className)
            }
        } catch (e: ClassNotFoundException) {
            LOG.error("Class not found: {}", className, e)
            null
        } catch (e: Exception) {
            LOG.error("Failed to load class: {}", className, e)
            null
        }
    }

    private fun getOrCreateLoader(dexFile: File): DexClassLoader {
        val runtimeDex = runtimeDexFile
        val hasRuntimeDex = runtimeDex != null && runtimeDex.exists()

        val dexFiles = mutableListOf<File>()
        dexFiles.add(dexFile)
        dexFiles.addAll(projectDexFiles)
        if (hasRuntimeDex) {
            dexFiles.add(runtimeDex!!)
        }

        val cacheKey = buildCacheKey(dexFiles)
        val dexPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }

        LOG.info("getOrCreateLoader: runtimeDex={}, projectDexFiles={}, totalDexFiles={}",
            runtimeDex?.absolutePath ?: "null",
            projectDexFiles.size,
            dexFiles.size)

        if (currentCacheKey == cacheKey && currentLoader != null) {
            LOG.debug("Reusing existing DexClassLoader")
            return currentLoader!!
        }

        release()

        val optimizedDir = File(context.codeCacheDir, "compose_preview_opt")
        optimizedDir.deleteRecursively()
        optimizedDir.mkdirs()

        val loader = DexClassLoader(
            dexPath,
            optimizedDir.absolutePath,
            null,
            context.classLoader
        )

        currentLoader = loader
        currentCacheKey = cacheKey

        LOG.info("Created new DexClassLoader with {} DEX files: {}",
            dexFiles.size, dexPath)

        return loader
    }

    private fun buildCacheKey(dexFiles: List<File>): String {
        return dexFiles.joinToString("|") { file ->
            "${file.absolutePath}:${file.lastModified()}"
        }
    }

    fun release() {
        currentLoader = null
        currentCacheKey = null

        val optimizedDir = File(context.codeCacheDir, "compose_preview_opt")
        if (optimizedDir.exists()) {
            optimizedDir.deleteRecursively()
        }

        LOG.debug("Released ComposeClassLoader resources")
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComposeClassLoader::class.java)
    }
}
