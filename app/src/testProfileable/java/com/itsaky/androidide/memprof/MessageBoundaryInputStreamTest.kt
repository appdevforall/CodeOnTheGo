/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.memprof

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class MessageBoundaryInputStreamTest {
	private val starts = mutableListOf<Long>()

	@Test
	fun `reports the content length once the headers end, before any content byte is read`() {
		val stream = streamOf(frame("{\"a\":1}"))

		readHeaders(stream)

		assertThat(starts).containsExactly(7L)
	}

	@Test
	fun `does not mistake a header-like line inside the content for a new message`() {
		val content = "Content-Length: 99\r\n\r\n"
		val stream = streamOf(frame(content))

		stream.readBytes()

		assertThat(starts).containsExactly(content.length.toLong())
	}

	@Test
	fun `reports each of several messages delivered in one bulk read`() {
		val stream = streamOf(frame("{}") + frame("{\"b\":22}") + frame(""))

		stream.readBytes()

		assertThat(starts).containsExactly(2L, 8L, 0L).inOrder()
	}

	@Test
	fun `matches the content length header case-insensitively and ignores other headers`() {
		val bytes = "content-type: application/json\r\ncontent-length: 3\r\n\r\n[1]".toByteArray()
		val stream = streamOf(bytes)

		stream.readBytes()

		assertThat(starts).containsExactly(3L)
	}

	@Test
	fun `reports nothing for a header block without a content length`() {
		val stream = streamOf("Content-Type: x\r\n\r\n".toByteArray() + frame("{}"))

		stream.readBytes()

		assertThat(starts).containsExactly(2L)
	}

	private fun streamOf(bytes: ByteArray) = MessageBoundaryInputStream(ByteArrayInputStream(bytes)) { starts += it }

	// Reads the way lsp4j's StreamMessageProducer does: headers one byte at a time, up to the blank line.
	private fun readHeaders(stream: MessageBoundaryInputStream) {
		val line = StringBuilder()
		while (true) {
			val c = stream.read().toChar()
			if (c == '\n') {
				if (line.toString().trimEnd('\r').isEmpty()) return
				line.setLength(0)
			} else {
				line.append(c)
			}
		}
	}

	private fun frame(content: String): ByteArray {
		val body = content.toByteArray()
		return "Content-Length: ${body.size}\r\n\r\n".toByteArray() + body
	}
}
