package org.appdevforall.codeonthego.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.plugins.services.LogEntry
import org.appdevforall.codeonthego.plugins.services.LogLevel
import org.appdevforall.codeonthego.plugins.services.LogQuery
import org.appdevforall.codeonthego.plugins.services.LogReadResult
import org.appdevforall.codeonthego.plugins.services.LogSource
import org.junit.Test

class IdeLogServiceImplTest {
	private val service = IdeLogServiceImpl()

	@Test
	fun `read before the app installs a reader is empty`() {
		assertThat(service.readLogs(LogSource.APP, LogQuery())).isEqualTo(LogReadResult.EMPTY)
	}

	@Test
	fun `read passes source and query through to the reader`() {
		val expected = LogReadResult(listOf(LogEntry(LogLevel.ERROR, "boom")), truncated = false)
		var seen: Pair<LogSource, LogQuery>? = null
		service.setLogReader { source, query ->
			seen = source to query
			expected
		}

		val query = LogQuery(text = "boom")
		assertThat(service.readLogs(LogSource.IDE, query)).isEqualTo(expected)
		assertThat(seen).isEqualTo(LogSource.IDE to query)
	}

	@Test
	fun `a throwing reader yields an empty result instead of a throw`() {
		service.setLogReader { _, _ -> throw IllegalStateException("host failure") }

		assertThat(service.readLogs(LogSource.APP, LogQuery())).isEqualTo(LogReadResult.EMPTY)
	}

	@Test
	fun `a reader throwing an Error also yields an empty result`() {
		service.setLogReader { _, _ -> throw NoClassDefFoundError("mismatched host") }

		assertThat(service.readLogs(LogSource.APP, LogQuery())).isEqualTo(LogReadResult.EMPTY)
	}

	@Test(expected = OutOfMemoryError::class)
	fun `a VirtualMachineError is not swallowed`() {
		service.setLogReader { _, _ -> throw OutOfMemoryError("simulated") }

		service.readLogs(LogSource.APP, LogQuery())
	}
}
