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

import com.itsaky.androidide.services.builder.ToolingMessageTap
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import java.io.InputStream

/**
 * Measures what the tooling server sends the IDE, so the cost of a build in the IDE's heap can be
 * told apart from the Gradle daemon's.
 *
 * Each build is a [Memprof] phase from `client/prepareBuild` to `client/onBuildSuccessful` or
 * `client/onBuildFailed`, reporting message counts and content bytes per JSON-RPC method. Every
 * message of at least [LARGE_MESSAGE_BYTES] is also a [Memprof] section of its own, opened once its
 * headers are read so it covers reading, decoding and parsing the content as well as handling it.
 *
 * [MemprofSession] installs it as the [ToolingMessageTap] factory. It logs a line per
 * [PROGRESS_EVERY] messages to logcat (see [MemprofLog] for why not slf4j).
 */
internal class ToolingMessageProbe : ToolingMessageTap {
	private var size = 0L

	@Volatile
	private var largeMessage: MemprofSpan? = null

	@Volatile
	private var build: BuildStats? = null

	/** Follows the server's message framing, to size each message before it is read. */
	override fun wrapInput(input: InputStream): InputStream = MessageBoundaryInputStream(input, ::onContentStart)

	/** Wraps the consumer of incoming messages; outgoing ones pass through untouched. */
	override fun wrapMessages(consumer: MessageConsumer): MessageConsumer =
		if (consumer is RemoteEndpoint) {
			MessageConsumer { message -> receive(message, consumer) }
		} else {
			consumer
		}

	/** Abandons the open spans, in case the server exited mid-build or mid-message. */
	override fun onServerStopped() {
		abandonLargeMessage()
		abandonBuild()
	}

	private fun abandonLargeMessage() {
		val open = largeMessage
		largeMessage = null
		open?.abandon()
	}

	private fun abandonBuild() {
		val open = build
		build = null
		open?.span?.abandon()
	}

	/*
	 * Runs on the launcher's single reader thread, as does receive(), so each message's start is
	 * seen before that message is handed over.
	 */
	private fun onContentStart(contentLength: Long) {
		// A message that failed to parse never reached receive(), so its section is still open.
		abandonLargeMessage()
		size = contentLength
		if (contentLength >= LARGE_MESSAGE_BYTES) {
			largeMessage = Memprof.beginSection("Tooling message", "bytes=$contentLength")
		}
	}

	private fun receive(
		message: Message,
		delegate: MessageConsumer,
	) {
		val method = methodOf(message)
		val section = largeMessage
		largeMessage = null

		if (method == PREPARE_BUILD) {
			abandonBuild()
			build = BuildStats(Memprof.beginPhase("Run Gradle build", "build_complete"))
		}
		build?.record(method, size)

		if (section != null) {
			log.i("MEMPROF|tooling_large_message|{}|method={}|bytes={}", System.currentTimeMillis(), method, size)
			section.put("bytes", size)
			try {
				delegate.consume(message)
				section.end()
			} finally {
				section.abandon()
			}
		} else {
			delegate.consume(message)
		}

		if (method == BUILD_SUCCESSFUL || method == BUILD_FAILED) {
			build?.finish()
			build = null
		}
	}

	private fun methodOf(message: Message): String =
		when (message) {
			is NotificationMessage -> message.method
			is RequestMessage -> message.method
			else -> "response"
		}

	private class BuildStats(
		val span: MemprofSpan,
	) {
		private val perMethod = LinkedHashMap<String, LongArray>()
		private var messages = 0L
		private var bytes = 0L
		private var maxBytes = 0L
		private var maxMethod = ""

		fun record(
			method: String,
			size: Long,
		) {
			messages++
			bytes += size
			if (size > maxBytes) {
				maxBytes = size
				maxMethod = method
			}
			val counts = perMethod.getOrPut(method) { LongArray(2) }
			counts[0]++
			counts[1] += size
			if (messages % PROGRESS_EVERY == 0L) {
				log.i(
					"MEMPROF|tooling_messages|{}|messages={}|bytes={}|maxBytes={}|maxMethod={}",
					System.currentTimeMillis(),
					messages,
					bytes,
					maxBytes,
					maxMethod,
				)
			}
		}

		fun finish() {
			span.put("messages", messages)
			span.put("bytes", bytes)
			span.put("maxMessageBytes", maxBytes)
			for ((method, counts) in perMethod) {
				val name = method.substringAfterLast('/')
				span.put("$name.count", counts[0])
				span.put("$name.bytes", counts[1])
			}
			span.end()
		}
	}

	companion object {
		private val log = MemprofLog("ToolingMessageProbe")

		private const val PREPARE_BUILD = "client/prepareBuild"
		private const val BUILD_SUCCESSFUL = "client/onBuildSuccessful"
		private const val BUILD_FAILED = "client/onBuildFailed"

		/** A message this big gets its own section; a log line is a few hundred bytes. */
		const val LARGE_MESSAGE_BYTES = 256L * 1024

		/** How often, in messages, a build logs its running totals. */
		const val PROGRESS_EVERY = 1000L
	}
}
