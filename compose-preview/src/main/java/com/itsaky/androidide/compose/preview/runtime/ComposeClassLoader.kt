package com.itsaky.androidide.compose.preview.runtime

import android.content.Context
import dalvik.system.DexClassLoader
import org.slf4j.LoggerFactory
import java.io.File

class ComposeClassLoader(private val context: Context) {

    private var currentLoader: DexClassLoader? = null
    private var currentDexPath: String? = null
    private var runtimeDexFile: File? = null
    private var projectDexFiles: List<File> = emptyList()

    fun setRuntimeDex(runtimeDex: File?) {
        LOG.info("setRuntimeDex called: {} (current: {})",
            runtimeDex?.absolutePath ?: "null",
            runtimeDexFile?.absolutePath ?: "null")
        if (runtimeDex != runtimeDexFile) {
            runtimeDexFile = runtimeDex
            release()
            LOG.info("Runtime DEX updated to: {}", runtimeDex?.absolutePath ?: "null")
        }
    }

    fun setProjectDexFiles(dexFiles: List<File>) {
        val existingFiles = dexFiles.filter { it.exists() }
        LOG.info("setProjectDexFiles called: {} files ({} exist)",
            dexFiles.size, existingFiles.size)
        if (existingFiles != projectDexFiles) {
            projectDexFiles = existingFiles
            release()
            existingFiles.forEach { LOG.info("  Project DEX: {}", it.absolutePath) }
        }
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

        val dexPaths = mutableListOf<String>()
        dexPaths.add(dexFile.absolutePath)
        projectDexFiles.forEach { dexPaths.add(it.absolutePath) }
        if (hasRuntimeDex) {
            dexPaths.add(runtimeDex!!.absolutePath)
        }

        val dexPath = dexPaths.joinToString(File.pathSeparator)

        LOG.info("getOrCreateLoader: runtimeDex={}, projectDexFiles={}, totalDexFiles={}",
            runtimeDex?.absolutePath ?: "null",
            projectDexFiles.size,
            dexPaths.size)

        if (currentDexPath == dexPath && currentLoader != null) {
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
        currentDexPath = dexPath

        LOG.info("Created new DexClassLoader with {} DEX files: {}",
            dexPaths.size, dexPath)

        return loader
    }

    fun release() {
        currentLoader = null
        currentDexPath = null

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
