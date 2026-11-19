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

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Follows the `Content-Length` framing of a JSON-RPC stream and calls [onContentStart] with each
 * message's content length as soon as its header block has been read.
 *
 * lsp4j reads the header block a byte at a time, so the callback runs before the reader has read
 * any of the content, let alone decoded or parsed it.
 */
internal class MessageBoundaryInputStream(
	input: InputStream,
	private val onContentStart: (contentLength: Long) -> Unit,
) : FilterInputStream(input) {
	private val headerLine = StringBuilder()
	private var contentLength = -1L
	private var contentRemaining = 0L

	override fun read(): Int =
		super.read().also { byte ->
			if (byte >= 0) observe(byte.toByte())
		}

	override fun read(
		buffer: ByteArray,
		offset: Int,
		length: Int,
	): Int =
		super.read(buffer, offset, length).also { n ->
			var i = offset
			val end = offset + maxOf(n, 0)
			while (i < end) {
				if (contentRemaining > 0) {
					val skipped = minOf(contentRemaining, (end - i).toLong())
					contentRemaining -= skipped
					i += skipped.toInt()
				} else {
					observeHeader(buffer[i++])
				}
			}
		}

	private fun observe(byte: Byte) {
		if (contentRemaining > 0) {
			contentRemaining--
		} else {
			observeHeader(byte)
		}
	}

	private fun observeHeader(byte: Byte) {
		val c = (byte.toInt() and 0xff).toChar()
		if (c != '\n') {
			headerLine.append(c)
			return
		}
		val line = headerLine.trimEnd('\r')
		headerLine.setLength(0)
		if (line.isNotEmpty()) {
			parseHeader(line)
		} else if (contentLength >= 0) {
			contentRemaining = contentLength
			val length = contentLength
			contentLength = -1
			onContentStart(length)
		}
	}

	private fun parseHeader(line: CharSequence) {
		val colon = line.indexOf(':')
		if (colon > 0 && line.substring(0, colon).trim().equals(CONTENT_LENGTH, ignoreCase = true)) {
			contentLength = line.substring(colon + 1).trim().toLongOrNull() ?: -1
		}
	}

	private companion object {
		const val CONTENT_LENGTH = "Content-Length"
	}
}
