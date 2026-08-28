package com.itsaky.androidide.logging.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.event.Level
import java.util.Collections

/** [IdeLogRouter] is a process-wide singleton; every sink registered here is removed again. */
@RunWith(JUnit4::class)
class IdeLogRouterTest {
	private data class Received(
		val level: Level,
		val loggerName: String,
		val message: String,
		val throwable: Throwable?,
	)

	@Test
	fun `a registered sink receives dispatched events`() {
		val received = Collections.synchronizedList(mutableListOf<Received>())
		val sink =
			IdeLogRouter.ExternalSink { level, loggerName, message, throwable ->
				received.add(Received(level, loggerName, message, throwable))
			}

		IdeLogRouter.addSink(sink)
		try {
			IdeLogRouter.dispatch(Level.INFO, "com.itsaky.androidide.Foo", "hello", null)
		} finally {
			IdeLogRouter.removeSink(sink)
		}

		assertThat(received).contains(Received(Level.INFO, "com.itsaky.androidide.Foo", "hello", null))
	}

	@Test
	fun `removeSink stops further delivery to that sink`() {
		val received = Collections.synchronizedList(mutableListOf<Received>())
		val sink =
			IdeLogRouter.ExternalSink { level, loggerName, message, throwable ->
				received.add(Received(level, loggerName, message, throwable))
			}

		IdeLogRouter.addSink(sink)
		IdeLogRouter.removeSink(sink)
		IdeLogRouter.dispatch(Level.INFO, "Foo", "should not arrive", null)

		assertThat(received).isEmpty()
	}

	@Test
	fun `a throwing sink does not stop other sinks from receiving the event`() {
		val received = Collections.synchronizedList(mutableListOf<Received>())
		val brokenSink = IdeLogRouter.ExternalSink { _, _, _, _ -> throw RuntimeException("boom") }
		val healthySink =
			IdeLogRouter.ExternalSink { level, loggerName, message, throwable ->
				received.add(Received(level, loggerName, message, throwable))
			}

		IdeLogRouter.addSink(brokenSink)
		IdeLogRouter.addSink(healthySink)
		try {
			IdeLogRouter.dispatch(Level.ERROR, "Foo", "still delivered", null)
		} finally {
			IdeLogRouter.removeSink(brokenSink)
			IdeLogRouter.removeSink(healthySink)
		}

		assertThat(received).contains(Received(Level.ERROR, "Foo", "still delivered", null))
	}

	@Test
	fun `dispatch never throws even when every sink is broken`() {
		val brokenSink = IdeLogRouter.ExternalSink { _, _, _, _ -> throw IllegalStateException("boom") }

		IdeLogRouter.addSink(brokenSink)
		try {
			IdeLogRouter.dispatch(Level.WARN, "Foo", "message", RuntimeException("cause"))
		} finally {
			IdeLogRouter.removeSink(brokenSink)
		}
	}
}
