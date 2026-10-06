package org.appdevforall.codeonthego.plugins.extensions

import org.appdevforall.codeonthego.plugins.IPlugin

interface SnippetExtension : IPlugin {
	fun getSnippetContributions(): List<SnippetContribution>
}

data class SnippetContribution(
	val language: String,
	val scope: String,
	val prefix: String,
	val description: String,
	val body: List<String>,
)