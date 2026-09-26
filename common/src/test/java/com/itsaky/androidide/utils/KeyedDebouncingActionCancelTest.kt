package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Repro for ADFA-4328: cancelling a [KeyedDebouncingAction] entry whose worker is
 * parked on `channel.receive()` must NOT let a [ClosedReceiveChannelException]
 * escape to the scope's uncaught-exception handler.
 *
 * `ActionEntry.cancel()` must therefore call `job.cancel()` BEFORE `channel.close()`, and
 * the worker loop must swallow [ClosedReceiveChannelException] as well. Closing the channel
 * first wakes the parked `receive()` with a [ClosedReceiveChannelException] - NOT a
 * CancellationException - which propagates uncaught to the [CoroutineExceptionHandler].
 */
class KeyedDebouncingActionCancelTest {
	/** Cancelling an entry whose worker is parked on receive() must not surface an uncaught exception. */
	@Test
	fun `cancelling a parked worker does not leak a ClosedReceiveChannelException`() =
		runBlocking {
			val uncaught = AtomicReference<Throwable?>(null)
			// A plain Job (not Supervisor of the worker) + a handler that records anything
			// that escapes the debounce worker coroutine.
			val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
			val scope = CoroutineScope(SupervisorJob() + handler)

			val ctx: CoroutineContext = scope.coroutineContext

			// Signalled from inside the action, so the test knows the worker really got that
			// far. A fixed delay cannot tell "the worker is parked on receive()" apart from
			// "the worker never started" - and in the second case the cancellation raises a
			// CancellationException the handler never sees, so the repro silently does not run
			// and the test still reports green.
			val actionRan = CompletableDeferred<Unit>()

			val debouncer =
				KeyedDebouncingAction<String>(
					scope = scope,
					debounceDuration = 50.milliseconds,
					actionContext = ctx,
					action = { _, _ -> actionRan.complete(Unit) },
				)

			// schedule() creates the entry + launches the worker. With a CONFLATED channel and
			// no further sends, the worker debounces the single key, runs the action, then
			// loops back and parks on channel.receive() waiting for the next key.
			debouncer.schedule("k")

			// Fails loudly rather than passing vacuously if the worker never reached the action.
			withTimeout(5_000) { actionRan.await() }
			assertThat(actionRan.isCompleted).isTrue()

			// The action has returned; the remaining hop is joining the action job and looping
			// back to the park, which has no observable signal of its own.
			delay(100)

			// Cancel the entry while the worker is parked on receive().
			debouncer.cancelPending("k")

			// Let any uncaught exception propagate to the handler.
			delay(200)

			val leaked = uncaught.get()
			assertThat(leaked).isNull()
		}
}
