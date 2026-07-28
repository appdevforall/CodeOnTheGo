package com.itsaky.androidide.logging.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.event.Level
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [IdeGlobalLogBuffer] is a process-wide singleton whose buffer is never cleared between
 * tests, so every test uses a unique marker string and only asserts on lines containing it.
 */
@RunWith(JUnit4::class)
class IdeGlobalLogBufferTest {
	private class RecordingConsumer(
		override val logLevel: Level,
	) : IdeGlobalLogBuffer.Consumer {
		val received = Collections.synchronizedList(mutableListOf<String>())

		override fun consume(
			level: Level,
			message: String,
		) {
			received.add(message)
		}
	}

	private fun withConsumer(
		consumer: IdeGlobalLogBuffer.Consumer,
		block: () -> Unit,
	) {
		IdeGlobalLogBuffer.registerConsumer(consumer)
		try {
			block()
		} finally {
			IdeGlobalLogBuffer.unregisterConsumer(consumer)
		}
	}

	@Test
	fun `registerConsumer replays previously buffered messages`() {
		val marker = "replay-marker-${System.nanoTime()}"
		IdeGlobalLogBuffer.append(Level.INFO, marker)

		val consumer = RecordingConsumer(Level.INFO)
		withConsumer(consumer) {
			assertThat(consumer.received.any { it.contains(marker) }).isTrue()
		}
	}

	@Test
	fun `consumer only receives messages at or above its own level`() {
		val marker = "level-filter-marker-${System.nanoTime()}"
		val consumer = RecordingConsumer(Level.WARN)

		withConsumer(consumer) {
			IdeGlobalLogBuffer.append(Level.INFO, marker)
			IdeGlobalLogBuffer.append(Level.ERROR, marker)

			assertThat(consumer.received.count { it.contains(marker) }).isEqualTo(1)
		}
	}

	@Test
	fun `unregisterConsumer stops further delivery`() {
		val marker = "unregister-marker-${System.nanoTime()}"
		val consumer = RecordingConsumer(Level.INFO)

		IdeGlobalLogBuffer.registerConsumer(consumer)
		IdeGlobalLogBuffer.unregisterConsumer(consumer)
		IdeGlobalLogBuffer.append(Level.INFO, marker)

		assertThat(consumer.received.any { it.contains(marker) }).isFalse()
	}

	@Test
	fun `buffer keeps only the most recent 1000 entries`() {
		val marker = "capacity-marker-${System.nanoTime()}"
		val oldestLine = "$marker-oldest"
		val newestLine = "$marker-newest"

		IdeGlobalLogBuffer.append(Level.INFO, oldestLine)
		repeat(1000) { IdeGlobalLogBuffer.append(Level.INFO, "$marker-filler-$it") }
		IdeGlobalLogBuffer.append(Level.INFO, newestLine)

		val consumer = RecordingConsumer(Level.INFO)
		withConsumer(consumer) {
			assertThat(consumer.received.any { it.contains(oldestLine) }).isFalse()
			assertThat(consumer.received.any { it.contains(newestLine) }).isTrue()
		}
	}

	@Test
	fun `concurrent register-unregister during dispatch never throws`() {
		val stop = AtomicBoolean(false)
		val failure =
			java.util.concurrent.atomic
				.AtomicReference<Throwable?>(null)
		val ready = CountDownLatch(1)
		val churned = CountDownLatch(1)

		val churner =
			Thread {
				ready.await()
				while (!stop.get()) {
					val consumer = RecordingConsumer(Level.INFO)
					IdeGlobalLogBuffer.registerConsumer(consumer)
					IdeGlobalLogBuffer.unregisterConsumer(consumer)
					churned.countDown()
				}
			}
		churner.setUncaughtExceptionHandler { _, error -> failure.set(error) }
		churner.start()

		ready.countDown()
		// Make sure the churner has actually registered/unregistered at least once before
		// dispatching, so this test can't silently pass without exercising the race at all.
		assertThat(churned.await(10, TimeUnit.SECONDS)).isTrue()

		repeat(2000) { IdeGlobalLogBuffer.append(Level.INFO, "concurrent-marker-$it") }
		stop.set(true)
		churner.join(TimeUnit.SECONDS.toMillis(10))

		assertThat(churner.isAlive).isFalse()
		assertThat(failure.get()).isNull()
	}
}
