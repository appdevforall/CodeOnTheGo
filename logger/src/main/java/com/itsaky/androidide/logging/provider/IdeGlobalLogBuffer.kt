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

package com.itsaky.androidide.logging.provider

import org.slf4j.event.Level
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of the Logback-backed `GlobalBufferAppender`: a ring buffer of formatted log lines
 * that makes recent logs available to the IDE Logs tab, independent of any particular
 * SLF4J backend.
 *
 * @author Akash Yadav
 */
object IdeGlobalLogBuffer {
	interface Consumer {
		val logLevel: Level

		fun consume(message: String)
	}

	private data class LogEvent(
		val level: Level,
		val message: String,
	)

	private const val MAX_BUFFER_SIZE = 1000
	private val buffer = ConcurrentLinkedQueue<LogEvent>()
	private val bufferSize = AtomicInteger(0)
	private val consumers = Collections.synchronizedList(mutableListOf<Consumer>())

	/**
	 * Register a consumer to receive log messages.
	 * The consumer will receive both new messages and all buffered messages.
	 */
	fun registerConsumer(consumer: Consumer) {
		consumers.add(consumer)
		buffer.forEach { event -> dispatchTo(consumer, event.level, event.message) }
	}

	/**
	 * Unregister a consumer.
	 */
	fun unregisterConsumer(consumer: Consumer) {
		consumers.remove(consumer)
	}

	fun append(
		level: Level,
		formattedMessage: String,
	) {
		val trimmed = formattedMessage.trim()

		buffer.offer(LogEvent(level, trimmed))
		if (bufferSize.incrementAndGet() > MAX_BUFFER_SIZE) {
			buffer.poll()
			bufferSize.decrementAndGet()
		}

		dispatch(level, trimmed)
	}

	private fun dispatch(
		level: Level,
		message: String,
	) {
		consumers.forEach { consumer -> dispatchTo(consumer, level, message) }
	}

	private fun dispatchTo(
		consumer: Consumer,
		level: Level,
		message: String,
	) {
		if (level.toInt() < consumer.logLevel.toInt()) return
		runCatching { consumer.consume(message) }
	}
}
