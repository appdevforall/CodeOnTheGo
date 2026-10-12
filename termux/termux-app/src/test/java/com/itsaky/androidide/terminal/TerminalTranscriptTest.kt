package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalTranscriptTest {
	@Test
	fun stripExitBannerKeepsTranscriptsWithoutOne() {
		assertThat(TerminalTranscript.stripExitBanner("$ ls\nREADME.md\n")).isEqualTo("$ ls\nREADME.md")
		assertThat(TerminalTranscript.stripExitBanner("$ ls\n[Process completed - press Enter]"))
			.isEqualTo("$ ls")
	}

	@Test
	fun stripExitBannerKeepsBannerTextInTheOutput() {
		val output = "$ cat log\n[Process completed - press Enter]\nnext line"
		assertThat(TerminalTranscript.stripExitBanner(output)).isEqualTo(output)
		assertThat(TerminalTranscript.stripExitBanner("$output\n[Process completed (signal 9) - press Enter]"))
			.isEqualTo(output)
	}
}
