package com.itsaky.androidide.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommandRecorderTest {
	private fun CommandRecorder.feed(text: String) = text.toByteArray().forEach(::onByte)

	@Test
	fun outputIsTheTextAsRenderedWithoutStyles() {
		val recorder = CommandRecorder(40, 10)

		recorder.feed("\$ ls\r\n\u001b[31mred\u001b[0m.txt\r\n")

		assertThat(recorder.output()).isEqualTo("\$ ls\nred.txt")
	}

	@Test
	fun carriageReturnOverwritesLikeTheTerminal() {
		val recorder = CommandRecorder(40, 10)

		recorder.feed("progress 10%\rprogress 99%\r\ndone\r\n")

		assertThat(recorder.output()).isEqualTo("progress 99%\ndone")
	}

	@Test
	fun exitBannerIsDropped() {
		val recorder = CommandRecorder(40, 10)

		recorder.feed("boom\r\n\r\n[Process completed (code 1) - press Enter]")

		assertThat(recorder.output()).isEqualTo("boom")
	}

	@Test
	fun aTinySessionStillRecords() {
		val recorder = CommandRecorder(0, 0)

		recorder.feed("ok")

		assertThat(recorder.output()).isEqualTo("ok")
	}

	@Test
	fun resizedRecorderPlacesTheCursorLikeTheResizedSession() {
		val recorder = CommandRecorder(20, 10)

		recorder.onResize(40, 10)
		// 25 characters wrap at 20 columns, so the carriage return would overwrite the wrapped "0".
		recorder.feed("0123456789012345678901234\ry")

		assertThat(recorder.output()).isEqualTo("y123456789012345678901234")
	}
}
