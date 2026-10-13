package org.appdevforall.codeonthego.plugins.services

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogQueryTest {
	@Test
	fun `default query reads every level with the default line count`() {
		val query = LogQuery()
		assertThat(query.levels).isEmpty()
		assertThat(query.text).isEmpty()
		assertThat(query.effectiveMaxLines).isEqualTo(LogQuery.DEFAULT_MAX_LINES)
	}

	@Test
	fun `max lines is clamped to the host range`() {
		assertThat(LogQuery(maxLines = 0).effectiveMaxLines).isEqualTo(1)
		assertThat(LogQuery(maxLines = -5).effectiveMaxLines).isEqualTo(1)
		assertThat(LogQuery(maxLines = 42).effectiveMaxLines).isEqualTo(42)
		assertThat(LogQuery(maxLines = Int.MAX_VALUE).effectiveMaxLines).isEqualTo(LogQuery.MAX_LINES)
	}
}
