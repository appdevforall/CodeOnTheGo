package com.itsaky.androidide.plugins.manager.snippets

import com.itsaky.androidide.plugins.extensions.SnippetContribution
import com.itsaky.androidide.plugins.extensions.SnippetExtension
import org.slf4j.LoggerFactory

class PluginSnippetManager private constructor() {

	companion object {
		private val log = LoggerFactory.getLogger(PluginSnippetManager::class.java)

		@Volatile
		private var instance: PluginSnippetManager? = null

		fun getInstance(): PluginSnippetManager {
			return instance ?: synchronized(this) {
				instance ?: PluginSnippetManager().also { instance = it }
			}
		}
	}

	private val extensions = mutableMapOf<String, SnippetExtension>()
	private val contributions = mutableMapOf<String, List<SnippetContribution>>()

	fun registerPlugin(pluginId: String, extension: SnippetExtension) {
		synchronized(contributions) {
			extensions[pluginId] = extension
		}

		val snippets = try {
			extension.getSnippetContributions()
		} catch (e: Exception) {
			log.error("Failed to get snippet contributions from plugin: {}", pluginId, e)
			emptyList()
		}

		synchronized(contributions) {
			contributions[pluginId] = snippets
		}
		log.info("Registered {} snippet contributions from plugin: {}", snippets.size, pluginId)
	}

	fun refreshPlugin(pluginId: String): List<SnippetContribution> {
		val extension = synchronized(contributions) { extensions[pluginId] } ?: return emptyList()
		val snippets = try {
			extension.getSnippetContributions()
		} catch (e: Exception) {
			log.error("Failed to refresh snippet contributions from plugin: {}", pluginId, e)
			return emptyList()
		}

		synchronized(contributions) {
			if (extensions[pluginId] == null) return emptyList()
			contributions[pluginId] = snippets
		}
		log.info("Refreshed {} snippet contributions from plugin: {}", snippets.size, pluginId)
		return snippets
	}

	fun getAllSnippets(): Map<String, List<SnippetContribution>> {
		synchronized(contributions) {
			return contributions.toMap()
		}
	}

	fun cleanupPlugin(pluginId: String) {
		synchronized(contributions) {
			extensions.remove(pluginId)
			contributions.remove(pluginId)
		}
		log.debug("Cleaned up snippet contributions for plugin: {}", pluginId)
	}
}
