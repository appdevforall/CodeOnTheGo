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
 * Inputs are indivisible: one input larger than [maxBatchChars] is emitted alone, while one larger
 * than [maxPendingChars] is omitted. Other inputs are never split or reordered.
 */
internal class BuildOutputBuffer(
	private val maxPendingChars: Int = DEFAULT_MAX_PENDING_CHARS,
	private val maxBatchChars: Int = DEFAULT_MAX_BATCH_CHARS,
) {
	data class Batch(
		val text: String,
		val sessionGeneration: Int,
	)

	private sealed interface Entry {
		val sessionGeneration: Int

		data class Text(
			val value: String,
			override val sessionGeneration: Int,
		) : Entry

		data class Omission(
			var lineCount: Long,
			override val sessionGeneration: Int,
		) : Entry
	}

	private val entries = ArrayDeque<Entry>()
	private val available = Channel<Unit>(Channel.CONFLATED)
	private val lock = Any()
	private var retainedChars = 0
	private var omission: Entry.Omission? = null

	internal val pendingChars: Int
		get() = synchronized(lock) { retainedChars }

	init {
		require(maxPendingChars > 0) { "maxPendingChars must be positive" }
		require(maxBatchChars > 0) { "maxBatchChars must be positive" }
	}

	fun offer(
		text: String,
		sessionGeneration: Int,
	) {
		if (text.isEmpty()) return
		val needsNewline = !text.endsWith('\n')
		val normalizedLength = text.length.toLong() + if (needsNewline) 1 else 0
		val lineCount = text.count { it == '\n' }.toLong() + if (needsNewline) 1 else 0
		synchronized(lock) {
			if (
				normalizedLength > maxPendingChars.toLong() ||
				normalizedLength > (maxPendingChars - retainedChars).toLong()
			) {
				val existingOmission = omission
				if (
					existingOmission?.sessionGeneration == sessionGeneration &&
					entries.lastOrNull() === existingOmission
				) {
					existingOmission.lineCount += lineCount
				} else {
					val marker = Entry.Omission(lineCount, sessionGeneration)
					omission = marker
					entries.addLast(marker)
				}
			} else {
				val normalized = if (needsNewline) "$text\n" else text
				entries.addLast(Entry.Text(normalized, sessionGeneration))
				retainedChars += normalizedLength.toInt()
			}
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
			omission = null
			while (available.tryReceive().isSuccess) {
				// Discard stale wakeups from the cleared build session.
			}
		}
	}

	private fun takeAvailableBatch(): Batch? {
		if (entries.isEmpty()) return null
		val sessionGeneration = entries.first().sessionGeneration
		val batch = StringBuilder(minOf(retainedChars, maxBatchChars))
		while (entries.isNotEmpty()) {
			val entry = entries.first()
			if (entry.sessionGeneration != sessionGeneration) break
			val value =
				when (entry) {
					is Entry.Text -> entry.value
					is Entry.Omission -> omissionMarker(entry.lineCount)
				}
			if (batch.isNotEmpty() && batch.length + value.length > maxBatchChars) break

			entries.removeFirst()
			batch.append(value)
			if (entry is Entry.Text) retainedChars -= entry.value.length
			if (entry is Entry.Omission && omission === entry) omission = null
		}
		if (entries.isNotEmpty()) available.trySend(Unit)
		return Batch(batch.toString(), sessionGeneration)
	}

	private fun omissionMarker(lineCount: Long): String = "[$lineCount build output lines omitted]\n"

	companion object {
		private const val DEFAULT_MAX_PENDING_CHARS = 256 * 1024
		private const val DEFAULT_MAX_BATCH_CHARS = 32 * 1024
	}
}
