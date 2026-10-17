package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Which SQLite versions [SQLiteIndex.optimize] analyzes on: those with `PRAGMA analysis_limit`, 3.32.0 and later. */
@RunWith(JUnit4::class)
class SQLiteIndexAnalysisLimitTest {
	@Test
	fun `versions from 3_32_0 on support the analysis limit`() {
		for (version in listOf("3.32.0", "3.32.2", "3.39.2", "3.51.2", "4.0")) {
			assertWithMessage(version).that(SQLiteIndex.supportsAnalysisLimit(version)).isTrue()
		}
	}

	@Test
	fun `versions before 3_32_0 do not`() {
		for (version in listOf("3.31.1", "3.28.0", "3.22.0", "2.99.99")) {
			assertWithMessage(version).that(SQLiteIndex.supportsAnalysisLimit(version)).isFalse()
		}
	}

	@Test
	fun `versions compare by number, not as text`() {
		// As text "3.9.0" sorts after "3.32.0"; as a version it is older.
		assertThat(SQLiteIndex.supportsAnalysisLimit("3.9.0")).isFalse()
	}

	@Test
	fun `an unparseable version does not`() {
		for (version in listOf("", "3.x", "unknown")) {
			assertWithMessage(version).that(SQLiteIndex.supportsAnalysisLimit(version)).isFalse()
		}
	}
}
