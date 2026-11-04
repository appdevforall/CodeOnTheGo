package com.itsaky.androidide.editor.language

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.api.ILanguageServer
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.TextRange
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LSPFormatterTest {
	@Test
	fun `reports a server failure instead of letting it escape`() {
		val failure = StackOverflowError()
		val server = mockk<ILanguageServer> { every { formatCode(any()) } throws failure }
		val reported = mutableListOf<Throwable>()
		val text = Content("fun main(")
		val cursor = TextRange(CharPosition(0, 3, 3), CharPosition(0, 3, 3))

		val range = LSPFormatter(server, reported::add).formatAsync(text, cursor)

		assertThat(reported).containsExactly(failure)
		assertThat(text.toString()).isEqualTo("fun main(")
		assertThat(range.start).isEqualTo(cursor.start)
		assertThat(range.end).isEqualTo(cursor.start)
	}
}
