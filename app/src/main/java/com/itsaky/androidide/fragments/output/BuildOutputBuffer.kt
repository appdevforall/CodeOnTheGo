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

package com.itsaky.androidide.fragments.output

import kotlinx.coroutines.channels.Channel

/**
 * Thread-safe pending build output with fixed memory and batch budgets.
 *
 * Inputs are indivisible unless one exceeds [maxPendingChars], in which case only its newest tail
 * is retained. Older pending output is evicted first, while batches preserve the retained order.
 */
internal class BuildOutputBuffer(
	private val maxPendingChars: Int = DEFAULT_MAX_PENDING_CHARS,
	private val maxBatchChars: Int = DEFAULT_MAX_BATCH_CHARS,
) {
	data class Batch(
		val text: String,
		val sessionToken: Int,
		val sourceChars: Int,
	)

	private sealed interface Entry {
		val sessionToken: Int

		data class Text(
			val value: String,
			override val sessionToken: Int,
		) : Entry

		data class Omission(
			var lineCount: Long,
			var sourceChars: Int,
			override val sessionToken: Int,
		) : Entry
	}

	private val entries = ArrayDeque<Entry>()
	private val available = Channel<Unit>(Channel.CONFLATED)
	private val lock = Any()
	private var retainedChars = 0

	internal val pendingChars: Int
		get() = synchronized(lock) { retainedChars }

	init {
		require(maxPendingChars > 0) { "maxPendingChars must be positive" }
		require(maxBatchChars > 0) { "maxBatchChars must be positive" }
	}

	fun offer(
		text: String,
		sessionToken: Int,
	) {
		if (text.isEmpty()) return
		val normalized = if (text.endsWith('\n')) text else "$text\n"
		synchronized(lock) {
			val existingOmission = entries.firstOrNull() as? Entry.Omission
			if (existingOmission != null) entries.removeFirst()

			var retained = normalized
			var omittedLines = existingOmission?.lineCount ?: 0
			var omittedChars = existingOmission?.sourceChars ?: 0
			if (retained.length > maxPendingChars) {
				val droppedPrefix = retained.dropLast(maxPendingChars)
				omittedLines += lineCount(droppedPrefix)
				omittedChars = saturatedAdd(omittedChars, droppedPrefix.length)
				retained = retained.takeLast(maxPendingChars)
			}
			while (retainedChars > maxPendingChars - retained.length) {
				val evicted = entries.removeFirst() as Entry.Text
				retainedChars -= evicted.value.length
				omittedLines += lineCount(evicted.value)
				omittedChars = saturatedAdd(omittedChars, evicted.value.length)
			}

			if (omittedLines > 0) {
				entries.addFirst(Entry.Omission(omittedLines, omittedChars, sessionToken))
			}
			entries.addLast(Entry.Text(retained, sessionToken))
			retainedChars += retained.length
			available.trySend(Unit)
		}
	}

	suspend fun takeBatch(): Batch {
		while (true) {
			available.receive()
			val batch = synchronized(lock) { takeAvailableBatch() }
			if (batch != null) return batch
		}
	}

	fun clear() {
		synchronized(lock) {
			entries.clear()
			retainedChars = 0
			while (available.tryReceive().isSuccess) {
				// Discard stale wakeups from the cleared build session.
			}
		}
	}

	private fun takeAvailableBatch(): Batch? {
		if (entries.isEmpty()) return null
		val sessionToken = entries.first().sessionToken
		val batch = StringBuilder(minOf(retainedChars, maxBatchChars))
		var sourceChars = 0
		while (entries.isNotEmpty()) {
			val entry = entries.first()
			if (entry.sessionToken != sessionToken) break
			val value =
				when (entry) {
					is Entry.Text -> entry.value
					is Entry.Omission -> omissionMarker(entry.lineCount)
				}
			if (batch.isNotEmpty() && batch.length + value.length > maxBatchChars) break

			entries.removeFirst()
			batch.append(value)
			when (entry) {
				is Entry.Text -> {
					retainedChars -= entry.value.length
					sourceChars = saturatedAdd(sourceChars, entry.value.length)
				}
				is Entry.Omission -> sourceChars = saturatedAdd(sourceChars, entry.sourceChars)
			}
		}
		if (entries.isNotEmpty()) available.trySend(Unit)
		return Batch(batch.toString(), sessionToken, sourceChars)
	}

	private fun lineCount(text: String): Long =
		text.count { it == '\n' }.toLong() + if (text.endsWith('\n')) 0 else 1

	private fun saturatedAdd(
		left: Int,
		right: Int,
	): Int = (left.toLong() + right).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

	private fun omissionMarker(lineCount: Long): String {
		val noun = if (lineCount == 1L) "line" else "lines"
		return "[$lineCount build output $noun omitted]\n"
	}

	companion object {
		private const val DEFAULT_MAX_PENDING_CHARS = 256 * 1024
		private const val DEFAULT_MAX_BATCH_CHARS = 32 * 1024
	}
}
