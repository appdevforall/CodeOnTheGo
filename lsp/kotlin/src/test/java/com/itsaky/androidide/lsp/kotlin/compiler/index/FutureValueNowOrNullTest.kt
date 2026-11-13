package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CompletableFuture

/**
 * Requirement: deciding whether a path's last refresh is reusable never throws, even when that refresh
 * fails between the caller's own completion check and the read of its value.
 */
internal class FutureValueNowOrNullTest {
	@Test
	fun `a failed future has no value`() {
		val failed = CompletableFuture<String>().apply { completeExceptionally(IllegalStateException("parse failed")) }

		assertThat(failed.valueNowOrNull()).isNull()
	}

	@Test
	fun `a cancelled future has no value`() {
		val cancelled = CompletableFuture<String>().apply { cancel(false) }

		assertThat(cancelled.valueNowOrNull()).isNull()
	}

	@Test
	fun `a pending future has no value`() {
		assertThat(CompletableFuture<String>().valueNowOrNull()).isNull()
	}

	@Test
	fun `a completed future yields its value`() {
		assertThat(CompletableFuture.completedFuture("v").valueNowOrNull()).isEqualTo("v")
	}
}
