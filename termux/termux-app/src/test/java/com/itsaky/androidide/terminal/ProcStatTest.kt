package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcStatTest {
	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun foregroundGroupIsTheTpgidField() {
		assertThat(ProcStat.parse("1234 (bash) S 1 1234 1234 34816 5678 4194560 0 0", ProcStat.TPGID_FIELD)).isEqualTo(5678)
	}

	@Test
	fun parentIsThePpidField() {
		assertThat(ProcStat.parse("1234 (bash) S 77 1234 1234 34816 5678 4194560 0 0", ProcStat.PPID_FIELD)).isEqualTo(77)
	}

	@Test
	fun commandNameWithSpacesAndParenthesesIsSkipped() {
		assertThat(ProcStat.parse("1234 (a) b (c) S 1 1234 1234 34816 5678 0", ProcStat.TPGID_FIELD)).isEqualTo(5678)
	}

	@Test
	fun processWithoutATerminalHasNoForegroundGroup() {
		assertThat(ProcStat.parse("1234 (bash) S 1 1234 1234 0 -1 0", ProcStat.TPGID_FIELD)).isNull()
	}

	@Test
	fun malformedStatIsNull() {
		assertThat(ProcStat.parse("", ProcStat.TPGID_FIELD)).isNull()
		assertThat(ProcStat.parse("1234 (bash) S 1", ProcStat.TPGID_FIELD)).isNull()
	}

	@Test
	fun statIsReadFromProc() {
		File(tmp.root, "1234").mkdirs()
		File(tmp.root, "1234/stat").writeText("1234 (bash) S 77 1234 1234 34816 1234 0\n")
		val proc = ProcStat(tmp.root)

		assertThat(proc.foregroundProcessGroup(1234)).isEqualTo(1234)
		assertThat(proc.parentOf(1234)).isEqualTo(77)
		assertThat(proc.foregroundProcessGroup(999)).isNull()
		assertThat(proc.parentOf(999)).isNull()
	}
}
