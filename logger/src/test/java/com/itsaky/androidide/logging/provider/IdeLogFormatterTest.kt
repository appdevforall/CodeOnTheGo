package com.itsaky.androidide.logging.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.event.Level

@RunWith(JUnit4::class)
class IdeLogFormatterTest {
	@Test
	fun `abbreviateLoggerName returns last segment of a qualified name`() {
		assertThat(IdeLogFormatter.abbreviateLoggerName("com.itsaky.androidide.Foo")).isEqualTo("Foo")
	}

	@Test
	fun `abbreviateLoggerName returns the name unchanged when there is no dot`() {
		assertThat(IdeLogFormatter.abbreviateLoggerName("Foo")).isEqualTo("Foo")
	}

	@Test
	fun `format includes the level, thread, tag and message`() {
		val formatted = IdeLogFormatter.format(Level.WARN, "com.itsaky.androidide.Foo", "hello world")

		assertThat(formatted).contains("WARN")
		assertThat(formatted).contains(Thread.currentThread().name)
		assertThat(formatted).contains("Foo:")
		assertThat(formatted).contains("hello world")
	}

	@Test
	fun `format ends with a trailing newline`() {
		assertThat(IdeLogFormatter.format(Level.INFO, "Foo", "msg")).endsWith("\n")
	}

	@Test
	fun `format with omitMessage drops the message but keeps the header`() {
		val formatted = IdeLogFormatter.format(Level.ERROR, "com.itsaky.androidide.Foo", "should not appear", omitMessage = true)

		assertThat(formatted).doesNotContain("should not appear")
		assertThat(formatted).contains("ERROR")
		assertThat(formatted).contains("Foo:")
		assertThat(formatted).endsWith("\n")
	}

	@Test
	fun `format timestamp matches the expected dd-MM HH-mm-ss-SS shape`() {
		val formatted = IdeLogFormatter.format(Level.DEBUG, "Foo", "msg")
		val timestampPattern = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{2} """)

		assertThat(timestampPattern.containsMatchIn(formatted)).isTrue()
	}

	@Test
	fun `appendThrowable leaves the message unchanged when there is no throwable`() {
		assertThat(IdeLogFormatter.appendThrowable("hello", null)).isEqualTo("hello")
	}

	@Test
	fun `appendThrowable appends the stack trace when a throwable is present`() {
		val result = IdeLogFormatter.appendThrowable("hello", RuntimeException("boom"))

		assertThat(result).startsWith("hello\n")
		assertThat(result).contains("RuntimeException")
		assertThat(result).contains("boom")
	}
}
