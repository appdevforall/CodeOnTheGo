package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
import java.io.File

class IdeTemplateServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val onTemplatesChanged: () -> Unit
) : IdeTemplateService {

    companion object {
        private val log = LoggerFactory.getLogger(IdeTemplateServiceImpl::class.java)
        private const val PLUGIN_PREFIX = "plugin_"
        private const val CGT_EXTENSION = "cgt"
    }

    private fun prefixedName(fileName: String): String {
        return "${PLUGIN_PREFIX}${pluginId}_$fileName"
    }

    override fun createTemplateBuilder(name: String): CgtTemplateBuilder {
        return CgtTemplateBuilder(name)
    }

    override fun registerTemplate(cgtFile: File): Boolean {
        if (!permissions.contains(PluginPermission.FILESYSTEM_WRITE)) {
            log.warn("Plugin $pluginId lacks FILESYSTEM_WRITE permission for template registration")
            return false
        }

        if (!cgtFile.exists() || cgtFile.extension != CGT_EXTENSION) {
            log.warn("Invalid template file for plugin $pluginId: ${cgtFile.absolutePath}")
            return false
        }

        val destFile = File(Environment.TEMPLATES_DIR, prefixedName(cgtFile.name))
        return try {
            cgtFile.copyTo(destFile, overwrite = true)
            log.info("Plugin $pluginId registered template: ${destFile.name}")
            runCatching { onTemplatesChanged() }
                .onFailure { log.warn("Failed to notify template change for plugin $pluginId", it) }
            true
        } catch (e: Exception) {
            log.error("Failed to register template for plugin $pluginId", e)
            false
        }
    }

    override fun unregisterTemplate(templateFileName: String): Boolean {
        val destFile = File(Environment.TEMPLATES_DIR, prefixedName(templateFileName))
        if (!destFile.exists()) return false

        return if (destFile.delete()) {
            log.info("Plugin $pluginId unregistered template: ${destFile.name}")
            runCatching { onTemplatesChanged() }
                .onFailure { log.warn("Failed to notify template change for plugin $pluginId", it) }
            true
        } else {
            log.warn("Failed to delete template file: ${destFile.name}")
            false
        }
    }

    override fun isTemplateRegistered(templateFileName: String): Boolean {
        return File(Environment.TEMPLATES_DIR, prefixedName(templateFileName)).exists()
    }

    override fun getRegisteredTemplates(): List<String> {
        val prefix = prefixedName("")
        return Environment.TEMPLATES_DIR
            .listFiles { file -> file.name.startsWith(prefix) && file.extension == CGT_EXTENSION }
            ?.map { it.name.removePrefix(prefix) }
            ?: emptyList()
    }

    override fun reloadTemplates() {
        onTemplatesChanged()
    }

    fun cleanupAllTemplates() {
        val prefix = prefixedName("")
        val deleted = Environment.TEMPLATES_DIR
            .listFiles { file -> file.name.startsWith(prefix) && file.extension == CGT_EXTENSION }
            ?.count { it.delete() }
            ?: 0

        if (deleted > 0) {
            runCatching { onTemplatesChanged() }
                .onFailure { log.warn("Failed to notify template change for plugin $pluginId", it) }
        }
    }
}
