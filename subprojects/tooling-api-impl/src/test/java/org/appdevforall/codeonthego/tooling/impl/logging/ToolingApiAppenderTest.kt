package org.appdevforall.codeonthego.tooling.impl.logging

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.tooling.api.IToolingApiClient
import org.appdevforall.codeonthego.tooling.api.messages.LogMessageParams
import org.appdevforall.codeonthego.tooling.impl.Main
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.event.Level

/**
 * [Main.client] has no production setter (only ever assigned once, when the tooling API
 * server actually connects), so this test uses [Main.setClientForTesting] to install a mock
 * for the duration of each test, and always clears it afterward.
 *
 * @author Akash Yadav
 */
@RunWith(JUnit4::class)
class ToolingApiAppenderTest {
	@After
	fun clearMainClient() {
		Main.setClientForTesting(null)
	}

	@Test
	fun `onLog forwards level, tag and message`() {
		val client = mockk<IToolingApiClient>(relaxed = true)
		Main.setClientForTesting(client)

		ToolingApiAppender.onLog(Level.WARN, "org.appdevforall.codeonthego.Foo", "hello world", null)

		val captured = slot<LogMessageParams>()
		verify { client.logMessage(capture(captured)) }
		assertThat(captured.captured.level).isEqualTo('W')
		assertThat(captured.captured.tag).isEqualTo("org.appdevforall.codeonthego.Foo")
		assertThat(captured.captured.message).contains("hello world")
	}

	@Test
	fun `onLog appends the throwable's stack trace to the message`() {
		val client = mockk<IToolingApiClient>(relaxed = true)
		Main.setClientForTesting(client)

		ToolingApiAppender.onLog(Level.ERROR, "Foo", "boom happened", RuntimeException("cause"))

		val captured = slot<LogMessageParams>()
		verify { client.logMessage(capture(captured)) }
		assertThat(captured.captured.level).isEqualTo('E')
		assertThat(captured.captured.message).contains("boom happened")
		assertThat(captured.captured.message).contains("RuntimeException")
		assertThat(captured.captured.message).contains("cause")
	}

	@Test
	fun `onLog does not throw when no client is connected`() {
		Main.setClientForTesting(null)

		ToolingApiAppender.onLog(Level.INFO, "Foo", "no client yet", null)
	}
}
