package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.manager.project.isTemplateOf
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.File
import java.io.IOException

class IdeTemplateServiceImpl(
	private val pluginId: String,
	private val permissions: Set<PluginPermission>,
	private val onTemplatesChanged: () -> Unit,
	private val loadedPluginIds: () -> Collection<String>,
) : IdeTemplateService {
	companion object {
		private val log = LoggerFactory.getLogger(IdeTemplateServiceImpl::class.java)
		private const val PLUGIN_PREFIX = "plugin_"
		private const val CGT_EXTENSION = "cgt"
		private const val COMPARE_CHUNK = 8192
	}

	private fun prefixedName(fileName: String): String = "${PLUGIN_PREFIX}${pluginId}_$fileName"

	override fun createTemplateBuilder(name: String): CgtTemplateBuilder = CgtTemplateBuilder(name)

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
			// Plugins register their templates on every activation. An identical copy is left
			// alone, and there is no change to report (ADFA-7287).
			if (destFile.hasSameContentAs(cgtFile)) {
				log.debug("Plugin {} template unchanged: {}", pluginId, destFile.name)
				return true
			}
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

	override fun isTemplateRegistered(templateFileName: String): Boolean =
		File(Environment.TEMPLATES_DIR, prefixedName(templateFileName)).exists()

	override fun getRegisteredTemplates(): List<String> {
		val prefix = prefixedName("")
		val others = otherPluginIds()
		return Environment.TEMPLATES_DIR
			.listFiles { file -> isTemplateOf(pluginId, file.name, others) }
			?.map { it.name.removePrefix(prefix) }
			?: emptyList()
	}

	private fun otherPluginIds() = loadedPluginIds() - pluginId

	// Compared in chunks, not read whole: a plugin may register a template of any size, such as one
	// built from the user's project. A failed read counts as a difference, so the copy is made.
	private fun File.hasSameContentAs(other: File): Boolean =
		try {
			isFile && length() == other.length() && sameBytes(this, other)
		} catch (e: IOException) {
			false
		}

	private fun sameBytes(
		x: File,
		y: File,
	): Boolean =
		DataInputStream(x.inputStream().buffered()).use { a ->
			DataInputStream(y.inputStream().buffered()).use { b ->
				val bufA = ByteArray(COMPARE_CHUNK)
				val bufB = ByteArray(COMPARE_CHUNK)
				var n = a.read(bufA)
				while (n != -1) {
					b.readFully(bufB, 0, n)
					for (i in 0 until n) {
						if (bufA[i] != bufB[i]) return false
					}
					n = a.read(bufA)
				}
				true
			}
		}

	override fun reloadTemplates() {
		onTemplatesChanged()
	}

	fun cleanupAllTemplates() {
		val others = otherPluginIds()
		val deleted =
			Environment.TEMPLATES_DIR
				.listFiles { file -> isTemplateOf(pluginId, file.name, others) }
				?.count { it.delete() }
				?: 0

		if (deleted > 0) {
			runCatching { onTemplatesChanged() }
				.onFailure { log.warn("Failed to notify template change for plugin $pluginId", it) }
		}
	}
}
