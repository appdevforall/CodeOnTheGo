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

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture

// Robolectric because the probe logs through android.util.Log; a plain Application keeps the IDE's
// own startup out of the test.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolingMessageProbeTest {
	private val events = mutableListOf<String>()
	private val probe = ToolingMessageProbe()

	@After
	fun uninstallSink() {
		Memprof.sink = null
	}

	@Test
	fun `a large message's section is open while its content is read, parsed and handled`() {
		Memprof.sink = RecordingSink(events)
		val content = notification("x".repeat(ToolingMessageProbe.LARGE_MESSAGE_BYTES.toInt()))

		deliver(frame(content))

		assertThat(events)
			.containsExactly(
				"begin section Tooling message bytes=${content.size}",
				"read content",
				"parse",
				"handle",
				"end section",
			).inOrder()
	}

	@Test
	fun `a small message gets no section`() {
		Memprof.sink = RecordingSink(events)

		deliver(frame(notification("small")))

		assertThat(events).containsExactly("read content", "parse", "handle").inOrder()
	}

	@Test
	fun `a large message that never reaches the consumer has its section abandoned`() {
		Memprof.sink = RecordingSink(events)
		val malformed = "{".repeat(ToolingMessageProbe.LARGE_MESSAGE_BYTES.toInt()).toByteArray()

		deliver(frame(malformed) + frame(notification("small")))

		assertThat(events).contains("abandon section")
		assertThat(events).doesNotContain("end section")
	}

	@Test
	fun `a large message still open when the server stops has its section abandoned`() {
		Memprof.sink = RecordingSink(events)
		val content = notification("x".repeat(ToolingMessageProbe.LARGE_MESSAGE_BYTES.toInt()))
		val input = probe.wrapInput(ByteArrayInputStream(frame(content)))
		input.read(ByteArray(64))

		probe.onServerStopped()

		assertThat(events).containsExactly("begin section Tooling message bytes=${content.size}", "abandon section").inOrder()
	}

	private fun deliver(bytes: ByteArray) {
		val handler =
			object : MessageJsonHandler(mapOf(METHOD to JsonRpcMethod.notification(METHOD, String::class.java))) {
				override fun parseMessage(input: CharSequence): Message {
					events += "parse"
					return super.parseMessage(input)
				}
			}
		val endpoint =
			object : Endpoint {
				override fun request(
					method: String,
					parameter: Any?,
				): CompletableFuture<*> = CompletableFuture.completedFuture(null)

				override fun notify(
					method: String,
					parameter: Any?,
				) {
					events += "handle"
				}
			}
		val input = probe.wrapInput(ContentReadRecorder(ByteArrayInputStream(bytes)))
		StreamMessageProducer(input, handler) { _, _ -> }
			.listen(probe.wrapMessages(RemoteEndpoint(MessageConsumer {}, endpoint)))
	}

	// lsp4j reads headers a byte at a time and content in bulk, so a bulk read is a content read.
	private inner class ContentReadRecorder(
		input: InputStream,
	) : FilterInputStream(input) {
		override fun read(
			buffer: ByteArray,
			offset: Int,
			length: Int,
		): Int =
			super.read(buffer, offset, length).also {
				if (events.lastOrNull() != "read content") events += "read content"
			}
	}

	private class RecordingSink(
		private val events: MutableList<String>,
	) : MemprofSink {
		override fun beginPhase(
			title: String,
			marker: String,
		): MemprofSpan = MemprofSpan.None

		override fun beginSection(
			name: String,
			detail: String?,
		): MemprofSpan {
			events += "begin section $name $detail"
			return object : MemprofSpan {
				private var finished = false

				override val isRecording = true

				override fun put(
					key: String,
					value: Long,
				) = Unit

				override fun end() = finish("end section")

				override fun abandon() = finish("abandon section")

				private fun finish(event: String) {
					if (!finished) {
						finished = true
						events += event
					}
				}
			}
		}

		override fun mark(marker: String) = Unit
	}

	private companion object {
		const val METHOD = "client/logOutput"

		fun notification(param: String) = """{"jsonrpc":"2.0","method":"$METHOD","params":"$param"}""".toByteArray()

		fun frame(content: ByteArray) = "Content-Length: ${content.size}\r\n\r\n".toByteArray() + content
	}
}
