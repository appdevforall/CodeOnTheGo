package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class KeyedDebouncingActionTest {
	@Test
	fun `scheduling a newer key cancels the running action's checker`() =
		runBlocking {
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val started = CompletableDeferred<Unit>()
			val cancelled = CompletableDeferred<Unit>()
			val debouncer =
				KeyedDebouncingAction<String>(
					scope = scope,
					debounceDuration = 10.milliseconds,
					action = { _, checker ->
						checker.invokeOnCancel { cancelled.complete(Unit) }
						started.complete(Unit)
						awaitCancellation()
					},
				)

			try {
				debouncer.schedule("a")
				withTimeout(2_000) { started.await() }
				debouncer.schedule("a")

				withTimeout(2_000) { cancelled.await() }
				assertThat(cancelled.isCompleted).isTrue()
			} finally {
				scope.cancel()
			}
		}
}
