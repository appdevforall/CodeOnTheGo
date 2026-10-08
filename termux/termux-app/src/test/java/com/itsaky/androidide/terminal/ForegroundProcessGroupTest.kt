package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ForegroundProcessGroupTest {
	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun foregroundGroupIsTheTpgidField() {
		assertThat(ForegroundProcessGroup.parse("1234 (bash) S 1 1234 1234 34816 5678 4194560 0 0")).isEqualTo(5678)
	}

	@Test
	fun commandNameWithSpacesAndParenthesesIsSkipped() {
		assertThat(ForegroundProcessGroup.parse("1234 (a) b (c) S 1 1234 1234 34816 5678 0")).isEqualTo(5678)
	}

	@Test
	fun processWithoutATerminalHasNoForegroundGroup() {
		assertThat(ForegroundProcessGroup.parse("1234 (bash) S 1 1234 1234 0 -1 0")).isNull()
	}

	@Test
	fun malformedStatIsNull() {
		assertThat(ForegroundProcessGroup.parse("")).isNull()
		assertThat(ForegroundProcessGroup.parse("1234 (bash) S 1")).isNull()
	}

	@Test
	fun statIsReadFromProc() {
		File(tmp.root, "1234").mkdirs()
		File(tmp.root, "1234/stat").writeText("1234 (bash) S 1 1234 1234 34816 1234 0\n")

		assertThat(ForegroundProcessGroup.of(1234, tmp.root)).isEqualTo(1234)
		assertThat(ForegroundProcessGroup.of(999, tmp.root)).isNull()
	}
}
