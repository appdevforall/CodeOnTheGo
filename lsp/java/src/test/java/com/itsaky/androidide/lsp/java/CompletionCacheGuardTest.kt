package com.itsaky.androidide.lsp.java

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.internal.model.CachedCompletion
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Paths

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class CompletionCacheGuardTest {
	@Test
	fun `store succeeds when the generation has not moved`() {
		val guard = CompletionCacheGuard()
		val generationAtStart = guard.currentGeneration()
		val candidate = newCachedCompletion()

		guard.store(candidate, generationAtStart)

		assertThat(guard.cachedCompletion).isSameInstanceAs(candidate)
	}

	@Test
	fun `a reset between start and store blocks the store`() {
		val guard = CompletionCacheGuard()
		val generationAtStart = guard.currentGeneration()

		// Simulates a full Idle -> Indexing -> Idle cycle completing while the completion that
		// captured generationAtStart is still computing.
		guard.reset()

		val staleResult = newCachedCompletion()
		guard.store(staleResult, generationAtStart)

		assertThat(guard.cachedCompletion).isNotSameInstanceAs(staleResult)
		assertThat(guard.cachedCompletion).isSameInstanceAs(CachedCompletion.EMPTY)
	}

	@Test
	fun `store after a reset with the current generation still succeeds`() {
		val guard = CompletionCacheGuard()
		guard.reset()
		val generationAfterReset = guard.currentGeneration()
		val candidate = newCachedCompletion()

		guard.store(candidate, generationAfterReset)

		assertThat(guard.cachedCompletion).isSameInstanceAs(candidate)
	}

	private fun newCachedCompletion(): CachedCompletion =
		CachedCompletion.cache(
			CompletionParams(Position.NONE, Paths.get("Test.java"), ICancelChecker.CANCELLED),
			CompletionResult.EMPTY,
		)
}
