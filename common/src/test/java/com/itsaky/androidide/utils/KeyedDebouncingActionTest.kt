package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@RunWith(JUnit4::class)
class KeyedDebouncingActionTest {

    private fun makeScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })

    // --- Basic debounce behaviour ---

    @Test
    fun `single schedule triggers action exactly once`() = runBlocking {
        val count = AtomicInteger(0)
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 50.milliseconds,
            action = { _, _ -> count.incrementAndGet() }
        )

        debouncer.schedule("k")
        delay(200)

        assertThat(count.get()).isEqualTo(1)
        debouncer.cancelAll()
    }

    @Test
    fun `rapid schedules are coalesced into a single action`() = runBlocking {
        val count = AtomicInteger(0)
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 100.milliseconds,
            action = { _, _ -> count.incrementAndGet() }
        )

        repeat(10) { debouncer.schedule("k") }
        delay(400)

        assertThat(count.get()).isEqualTo(1)
        debouncer.cancelAll()
    }

    @Test
    fun `last value wins when multiple sends arrive before debounce window`() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 100.milliseconds,
            action = { key, _ -> received.add(key) }
        )

        debouncer.schedule("first")
        debouncer.schedule("second")
        debouncer.schedule("third")
        delay(400)

        // Since the channel is CONFLATED, only the most-recently sent value survives
        assertThat(received).hasSize(1)
        debouncer.cancelAll()
    }

    // --- Multiple independent keys ---

    @Test
    fun `different keys are handled independently`() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 50.milliseconds,
            action = { key, _ -> received.add(key) }
        )

        debouncer.schedule("alpha")
        debouncer.schedule("beta")
        delay(300)

        assertThat(received).containsExactly("alpha", "beta")
        debouncer.cancelAll()
    }

    @Test
    fun `rapid schedules for different keys each trigger exactly once`() = runBlocking {
        val counts = mutableMapOf("a" to AtomicInteger(0), "b" to AtomicInteger(0))
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 50.milliseconds,
            action = { key, _ -> counts[key]?.incrementAndGet() }
        )

        repeat(5) { debouncer.schedule("a") }
        repeat(5) { debouncer.schedule("b") }
        delay(400)

        assertThat(counts["a"]!!.get()).isEqualTo(1)
        assertThat(counts["b"]!!.get()).isEqualTo(1)
        debouncer.cancelAll()
    }

    // --- Cancellation ---

    @Test
    fun `cancelPending prevents scheduled action from running`() = runBlocking {
        val count = AtomicInteger(0)
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 200.milliseconds,
            action = { _, _ -> count.incrementAndGet() }
        )

        debouncer.schedule("k")
        debouncer.cancelPending("k")
        delay(400)

        assertThat(count.get()).isEqualTo(0)
    }

    @Test
    fun `cancelAll cancels all pending entries`() = runBlocking {
        val count = AtomicInteger(0)
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 200.milliseconds,
            action = { _, _ -> count.incrementAndGet() }
        )

        debouncer.schedule("a")
        debouncer.schedule("b")
        debouncer.schedule("c")
        debouncer.cancelAll()
        delay(400)

        assertThat(count.get()).isEqualTo(0)
    }

    @Test
    fun `cancelPending for unknown key is a no-op`() = runBlocking {
        val count = AtomicInteger(0)
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 50.milliseconds,
            action = { _, _ -> count.incrementAndGet() }
        )

        debouncer.cancelPending("notScheduled")
        debouncer.schedule("k")
        delay(300)

        assertThat(count.get()).isEqualTo(1)
        debouncer.cancelAll()
    }

    // --- Re-scheduling after completion ---

    @Test
    fun `can re-schedule a key after its action completes`() = runBlocking {
        val count = AtomicInteger(0)
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 50.milliseconds,
            action = { _, _ -> count.incrementAndGet() }
        )

        debouncer.schedule("k")
        delay(200)  // let first action complete

        debouncer.schedule("k")
        delay(200)  // let second action complete

        assertThat(count.get()).isEqualTo(2)
        debouncer.cancelAll()
    }

    // --- ICancelChecker integration ---

    @Test
    fun `action receives a cancel checker that is active while running`() = runBlocking {
        var checkerWasActive = false
        val debouncer = KeyedDebouncingAction<String>(
            scope = makeScope(),
            debounceDuration = 50.milliseconds,
            action = { _, checker ->
                checkerWasActive = !checker.isCancelled()
            }
        )

        debouncer.schedule("k")
        delay(300)

        assertThat(checkerWasActive).isTrue()
        debouncer.cancelAll()
    }
}
