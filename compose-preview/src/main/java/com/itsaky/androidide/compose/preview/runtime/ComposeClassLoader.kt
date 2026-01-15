package com.itsaky.androidide.compose.preview.runtime

import android.content.Context
import dalvik.system.DexClassLoader
import org.slf4j.LoggerFactory
import java.io.File

class ComposeClassLoader(private val context: Context) {

    private var currentLoader: DexClassLoader? = null
    private var currentDexPath: String? = null

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
        val dexPath = dexFile.absolutePath

        if (currentDexPath == dexPath && currentLoader != null) {
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

        LOG.debug("Created new DexClassLoader for: {}", dexPath)

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
