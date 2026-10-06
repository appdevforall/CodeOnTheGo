package org.appdevforall.codeonthego.plugins.extensions

import org.appdevforall.codeonthego.plugins.IPlugin

interface LanguageExtension : IPlugin {
	fun getLanguages(): List<LanguageDefinition>
}

data class LanguageDefinition(
	val languageId: String,
	val fileExtensions: Set<String>,
	val grammar: TreeSitterGrammar? = null,
	val server: LanguageServerDefinition? = null,
)

data class TreeSitterGrammar(
	val name: String,
	val queriesAssetPath: String,
)

data class LanguageServerDefinition(
	val command: List<String>,
	val environment: Map<String, String> = emptyMap(),
	val initializationOptions: Map<String, Any?> = emptyMap(),
)
