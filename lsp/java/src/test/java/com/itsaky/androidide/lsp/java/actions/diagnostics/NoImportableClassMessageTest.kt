package com.itsaky.androidide.lsp.java.actions.diagnostics

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.service.IndexingState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NoImportableClassMessageTest {
	@Test
	fun `idle state formats only the template`() {
		val message =
			noImportableClassMessage(
				simpleName = "Stream",
				state = IndexingState.Idle,
				template = "No importable class named '%1\$s'.",
				indexingTemplate = "No importable class named '%1\$s'. Libraries are still being indexed.",
			)

		assertThat(message).isEqualTo("No importable class named 'Stream'.")
	}

	@Test
	fun `indexing state formats the indexing template instead of the plain one`() {
		val message =
			noImportableClassMessage(
				simpleName = "Stream",
				state = IndexingState.Indexing(done = 1, total = 5),
				template = "No importable class named '%1\$s'.",
				indexingTemplate = "No importable class named '%1\$s'. Libraries are still being indexed.",
			)

		assertThat(message).isEqualTo("No importable class named 'Stream'. Libraries are still being indexed.")
	}
}
