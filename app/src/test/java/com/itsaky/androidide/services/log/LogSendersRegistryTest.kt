package com.itsaky.androidide.services.log

import com.itsaky.androidide.logsender.ILogSender
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class LogSendersRegistryTest {
	// disconnectAll fills a plain list from forEach; a parallel walk would race on it.
	@Test
	fun `forEach visits every sender on the calling thread`() {
		val registry = LogSendersRegistry()
		repeat(8) { i ->
			val sender =
				mockk<ILogSender> {
					every { packageName } returns "pkg$i"
					every { id } returns "id$i"
				}
			registry.put(CachingLogSender(sender, port = 0, isStarted = true))
		}

		repeat(200) {
			val threads = mutableSetOf<Thread>()
			var visited = 0
			registry.forEach {
				threads.add(Thread.currentThread())
				visited++
			}
			assertEquals(setOf(Thread.currentThread()), threads)
			assertEquals(8, visited)
		}
	}
}
