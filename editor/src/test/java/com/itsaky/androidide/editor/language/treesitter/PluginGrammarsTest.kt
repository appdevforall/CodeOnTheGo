package com.itsaky.androidide.editor.language.treesitter

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PluginGrammarsTest {
	@Test
	fun refusesTheIdesGrammarNamesBeforeLoadingAnything() {
		for (name in listOf("java", "javascript", "json", "kotlin", "log", "xml")) {
			val error =
				assertThrows(PluginTreeSitterLanguage.GrammarLoadException::class.java) {
					PluginGrammars.acquire(File("/nonexistent/libtree-sitter-$name.so"), name)
				}
			assertThat(error).hasMessageThat().contains("is the IDE's own grammar")
		}
	}
}
