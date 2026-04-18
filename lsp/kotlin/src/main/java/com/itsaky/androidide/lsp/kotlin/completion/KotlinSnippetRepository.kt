package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.snippets.SnippetParser

object KotlinSnippetRepository {
	val snippets by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		SnippetParser.parse("kt", KotlinSnippetScope.entries)
	}
}