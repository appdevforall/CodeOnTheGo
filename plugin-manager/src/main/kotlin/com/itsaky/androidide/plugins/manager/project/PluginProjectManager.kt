package com.itsaky.androidide.plugins.manager.project

import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
import java.io.File

class PluginProjectManager private constructor() {

    companion object {
        private val log = LoggerFactory.getLogger(PluginProjectManager::class.java)

        @Volatile
        private var instance: PluginProjectManager? = null

        fun getInstance(): PluginProjectManager {
            return instance ?: synchronized(this) {
                instance ?: PluginProjectManager().also { instance = it }
            }
        }

        private const val PLUGIN_CGT_PREFIX = "plugin_"
        private const val CGT_EXTENSION = "cgt"
    }

    private var templateReloadListener: (() -> Unit)? = null

    fun setTemplateReloadListener(listener: (() -> Unit)?) {
        this.templateReloadListener = listener
    }

    fun extractBundledCgtTemplates(pluginId: String, classLoader: ClassLoader) {
        val searchPaths = listOf("templates/", "assets/templates/")

        val extracted = searchPaths.flatMap { basePath ->
            extractCgtFromPath(pluginId, classLoader, basePath)
        }

        if (extracted.isNotEmpty()) {
            templateReloadListener?.invoke()
        }
    }

    private fun extractCgtFromPath(
        pluginId: String,
        classLoader: ClassLoader,
        basePath: String
    ): List<File> {
        val entries = classLoader.getResourceAsStream(basePath)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: return emptyList()

        return entries
            .filter { it.endsWith(".$CGT_EXTENSION") }
            .mapNotNull { entry ->
                val inputStream = classLoader.getResourceAsStream("$basePath$entry") ?: return@mapNotNull null
                val destFile = File(Environment.TEMPLATES_DIR, "${PLUGIN_CGT_PREFIX}${pluginId}_$entry")

                runCatching {
                    inputStream.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    log.info("Extracted bundled template: {} from plugin {}", entry, pluginId)
                    destFile
                }.onFailure {
                    log.error("Failed to extract bundled template $entry from plugin $pluginId", it)
                }.getOrNull()
            }
    }

    fun cleanupPluginTemplates(pluginId: String) {
        val prefix = "${PLUGIN_CGT_PREFIX}${pluginId}_"
        val deleted = Environment.TEMPLATES_DIR
            .listFiles { file -> file.name.startsWith(prefix) && file.extension == CGT_EXTENSION }
            ?.count { file ->
                file.delete().also { if (it) log.debug("Cleaned up plugin template: {}", file.name) }
            }
            ?: 0

        if (deleted > 0) {
            log.info("Cleaned up {} template files for plugin {}", deleted, pluginId)
            templateReloadListener?.invoke()
        }
    }
}
