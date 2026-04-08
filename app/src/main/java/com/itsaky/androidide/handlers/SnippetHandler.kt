package com.itsaky.androidide.handlers

import com.itsaky.androidide.lsp.java.providers.snippet.JavaSnippetScope
import com.itsaky.androidide.lsp.snippets.DefaultSnippet
import com.itsaky.androidide.lsp.snippets.ISnippetScope
import com.itsaky.androidide.lsp.snippets.SnippetRegistry
import com.itsaky.androidide.lsp.snippets.UserSnippetLoader
import com.itsaky.androidide.lsp.xml.providers.snippet.XML_SNIPPET_SCOPES
import com.itsaky.androidide.plugins.manager.snippets.PluginSnippetManager
import org.slf4j.LoggerFactory

object SnippetHandler {

	private val log = LoggerFactory.getLogger(SnippetHandler::class.java)

	fun loadUserSnippets() {
		loadUserSnippetsForLanguage("java", JavaSnippetScope.entries.toTypedArray())
		loadUserSnippetsForLanguage("xml", XML_SNIPPET_SCOPES)
	}


	fun loadPluginSnippets() {

		val allSnippets = PluginSnippetManager.getInstance().getAllSnippets()
		allSnippets.forEach { (pluginId, contributions) ->
			contributions.forEach { contribution ->
				val snippet = DefaultSnippet(
					contribution.prefix,
					contribution.description,
					contribution.body.toTypedArray(),
				)
				SnippetRegistry.registerPluginSnippets(
					pluginId,
					contribution.language,
					contribution.scope,
					listOf(snippet),
				)
			}
		}
		if (allSnippets.isNotEmpty()) {
			log.info("Loaded plugin snippets from {} plugins", allSnippets.size)
		}
	}

	fun refreshPluginSnippets(pluginId: String) {
		SnippetRegistry.unregisterPluginSnippets(pluginId)
		val contributions = PluginSnippetManager.getInstance().refreshPlugin(pluginId)
		contributions.forEach { contribution ->
			val snippet = DefaultSnippet(
				contribution.prefix,
				contribution.description,
				contribution.body.toTypedArray(),
			)
			SnippetRegistry.registerPluginSnippets(
				pluginId,
				contribution.language,
				contribution.scope,
				listOf(snippet),
			)
		}
	}

	fun removePluginSnippets(pluginId: String) {
		SnippetRegistry.unregisterPluginSnippets(pluginId)
	}

	private fun <S : ISnippetScope> loadUserSnippetsForLanguage(
		language: String,
		scopes: Array<S>,
	) {
		val userSnippets = UserSnippetLoader.loadUserSnippets(language, scopes)
		userSnippets.forEach { (scope, snippets) ->
			if (snippets.isNotEmpty()) {
				SnippetRegistry.registerUserSnippets(language, scope, snippets)
			}
		}
		val total = userSnippets.values.sumOf { it.size }
		if (total > 0) {
			log.info("Loaded {} user snippets for {}", total, language)
		}
	}
}