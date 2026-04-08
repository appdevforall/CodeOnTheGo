package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin

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