package com.itsaky.androidide.lsp.java.providers

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.service.IndexingState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class CompletionProviderCacheDecisionTest {
	@Test
	fun `idle at both ends can be cached`() {
		assertThat(CompletionProvider.canCacheCompletion(IndexingState.Idle, IndexingState.Idle)).isTrue()
	}

	@Test
	fun `indexing at start cannot be cached`() {
		assertThat(
			CompletionProvider.canCacheCompletion(IndexingState.Indexing(done = 0, total = 1), IndexingState.Idle),
		).isFalse()
	}

	@Test
	fun `indexing at end cannot be cached`() {
		assertThat(
			CompletionProvider.canCacheCompletion(IndexingState.Idle, IndexingState.Indexing(done = 1, total = 2)),
		).isFalse()
	}

	@Test
	fun `indexing at both ends cannot be cached`() {
		assertThat(
			CompletionProvider.canCacheCompletion(
				IndexingState.Indexing(done = 0, total = 1),
				IndexingState.Indexing(done = 1, total = 1),
			),
		).isFalse()
	}
}
