package com.itsaky.androidide.lsp.kotlin.compiler

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.jvm.JVM_LIBRARY_SYMBOL_INDEX
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test

class KotlinProjectModelIndexTest {
	@Test
	fun `index getters read the bound registry, not the global manager`() {
		val registry = IndexRegistry()
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		val library = JvmSymbolIndex(backing, BackgroundIndexer(backing))
		registry.register(JVM_LIBRARY_SYMBOL_INDEX, library)

		val libraryIndex = runCatching { newModel(registry).libraryIndex }

		assertThat(libraryIndex.getOrNull()).isSameInstanceAs(library)
	}

	private fun newModel(registry: IndexRegistry) = KotlinProjectModel(registry)
}
