package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Repro for ADFA-4328: cancelling a [KeyedDebouncingAction] entry whose worker is
 * parked on `channel.receive()` must NOT let a [ClosedReceiveChannelException]
 * escape to the scope's uncaught-exception handler.
 *
 * On the pre-fix baseline, `ActionEntry.cancel()` did `channel.close()` BEFORE
 * `job.cancel()`. Closing the channel wakes the parked `receive()` with a
 * [ClosedReceiveChannelException] (NOT a CancellationException), which propagates
 * uncaught to the [CoroutineExceptionHandler] -> the Sentry crash this ticket fixes.
 *
 * The fix swaps the order (job.cancel() first) AND wraps the worker loop in a
 * try/catch that swallows ClosedReceiveChannelException, so no uncaught exception fires.
 */
class KeyedDebouncingActionCancelTest {

  @Test
  fun `cancelling a parked worker does not leak a ClosedReceiveChannelException`() = runBlocking {
    val uncaught = AtomicReference<Throwable?>(null)
    // A plain Job (not Supervisor of the worker) + a handler that records anything
    // that escapes the debounce worker coroutine.
    val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
    val scope = CoroutineScope(SupervisorJob() + handler)

    val ctx: CoroutineContext = scope.coroutineContext

    val debouncer = KeyedDebouncingAction<String>(
      scope = scope,
      debounceDuration = 50.milliseconds,
      actionContext = ctx,
      action = { _, _ -> /* never invoked: we cancel while parked on receive */ },
    )

    // schedule() creates the entry + launches the worker. With a CONFLATED channel and
    // no further sends, the worker debounces the single key, runs the (empty) action,
    // then loops back and parks on channel.receive() waiting for the next key.
    debouncer.schedule("k")

    // Give the worker time to: receive "k", run the empty action, loop, and PARK on
    // the next channel.receive(). 200ms >> 50ms debounce window.
    delay(200)

    // Cancel the entry while the worker is parked on receive().
    debouncer.cancelPending("k")

    // Let any uncaught exception propagate to the handler.
    delay(200)

    val leaked = uncaught.get()
    assertThat(leaked).isNull()
  }
}
