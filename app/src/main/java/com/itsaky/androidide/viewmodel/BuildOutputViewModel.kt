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
package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.preferences.internal.EditorPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * File-backed build output with a moving window in memory. All output is appended to a session
 * file; the UI can request the tail (for initial/restore view) or any range (for scroll). Full
 * content is read from file on demand for share/API. Memory is bounded by not holding the full
 * log in RAM.
 *
 * [appendAsync] is the write path and is safe to call from any thread; it does not depend on the
 * Build Output tab existing. That matters because the tab lives in a pager that destroys its
 * fragment whenever another tab is shown -- the AI agent's chat tab included -- and while the
 * fragment was the only caller of [append], a build started from the chat wrote no log at all and
 * the agent's `read_build_output` had nothing to read.
 */
class BuildOutputViewModel(
	application: Application,
) : AndroidViewModel(application) {
	private val lock = ReentrantLock()

	/**
	 * Case-insensitive line filter applied to the *editor view* of the build output.
	 * The session file always receives the unfiltered text.
	 */
	val filterText = MutableStateFlow("")

	/** Toggle for showing wall-clock timestamps `[HH:mm:ss.SSS]` in editor view. */
	val showTimestamps = MutableStateFlow(EditorPreferences.outputTimestamps)

	/** Toggle for showing step time deltas `ΔXms` in editor view. */
	val showDeltas = MutableStateFlow(EditorPreferences.outputDeltas)

	/** Toggle for showing gutter line numbers in editor view. */
	val showLineNumbers = MutableStateFlow(EditorPreferences.outputLineNumbers)

	/**
	 * Thread-safe snapshot of content for synchronous [getShareableContent] without blocking.
	 * Updated on [append] and [clear]; primed on restore via [setCachedSnapshot].
	 * Capped at [CACHE_SNAPSHOT_MAX_CHARS] to bound memory.
	 */
	@Volatile
	private var cachedContentSnapshot: String = ""

	/** Returns the current cached snapshot for share/copy (non-blocking). */
	fun getCachedContentSnapshot(): String = cachedContentSnapshot

	/** Updates the cached snapshot (e.g. after loading full content on restore). Capped to [CACHE_SNAPSHOT_MAX_CHARS]. */
	fun setCachedSnapshot(content: String) {
		cachedContentSnapshot =
			if (content.length <= CACHE_SNAPSHOT_MAX_CHARS) {
				content
			} else {
				content.takeLast(CACHE_SNAPSHOT_MAX_CHARS)
			}
	}

	private val sessionFile: File
		get() = File(getApplication<Application>().cacheDir, SESSION_FILE_NAME)

	/**
	 * Output waiting to be written. Unbounded and non-blocking to send: [appendAsync] is called from
	 * the Gradle tooling thread for every line of a build, which must never wait on disk.
	 */
	private val pendingOutput = Channel<PendingOutput>(Channel.UNLIMITED)

	/**
	 * Bumped by [clear]. A batch already drained when a new build starts belongs to the old session,
	 * and writing it would put the previous build's errors in front of the current build's.
	 */
	@Volatile
	private var sessionGeneration = 0

	init {
		viewModelScope.launch(Dispatchers.Default) { writePendingOutput() }
	}

	/**
	 * Queues [text] for the session file. Returns immediately; safe from any thread.
	 *
	 * @param text one line, or several, of build output; a missing trailing newline is added.
	 */
	fun appendAsync(text: String) {
		if (text.isEmpty()) return
		// Stamped here, not at drain time: a clear() between the queue handing an item to the writer
		// and the writer reading the counter would file the finished build's output under the new
		// session. The producer's moment is the one that decides which build the text belongs to.
		pendingOutput.trySend(
			PendingOutput(sessionGeneration, if (text.endsWith('\n')) text else text + "\n"),
		)
	}

	/**
	 * Drains [pendingOutput] for as long as the view model lives, batching whatever has piled up
	 * into one write: a large build emits thousands of lines, and one file open per line is the
	 * difference between a background write and a stutter.
	 */
	private suspend fun writePendingOutput() {
		val batch = StringBuilder()
		for (first in pendingOutput) {
			var generation = first.generation
			batch.append(first.text)
			while (true) {
				val next = pendingOutput.tryReceive().getOrNull() ?: break
				// A batch spans one session only, so a clear() mid-drain flushes what came before it.
				if (next.generation != generation) {
					appendForSession(batch.toString(), generation)
					batch.setLength(0)
					generation = next.generation
				}
				batch.append(next.text)
			}
			appendForSession(batch.toString(), generation)
			batch.setLength(0)
		}
	}

	/**
	 * One queued piece of build output.
	 *
	 * @property generation the session it was produced in; see [sessionGeneration].
	 * @property text the output, newline-terminated.
	 */
	private data class PendingOutput(
		val generation: Int,
		val text: String,
	)

	/**
	 * Appends text to the session file. File I/O is performed on a background dispatcher; call from
	 * any thread. Prefer calling before switching to Main so disk write does not block the UI.
	 */
	suspend fun append(text: String) = appendForSession(text, sessionGeneration)

	/**
	 * Appends [text] only while [generation] is still the current session.
	 *
	 * The check lives inside the lock, with the write: checked outside, a batch that had already
	 * passed it could still reach the disk after [clear] had deleted the file, seeding the new
	 * build's log with the finished build's errors.
	 *
	 * @param text the output to write.
	 * @param generation the session the text was produced in.
	 */
	private suspend fun appendForSession(
		text: String,
		generation: Int,
	) {
		if (text.isEmpty()) return
		withContext(Dispatchers.IO) {
			lock.withLock {
				if (generation != sessionGeneration) return@withLock
				try {
					FileOutputStream(sessionFile, true).use {
						it.write(text.toByteArray(StandardCharsets.UTF_8))
					}
					cachedContentSnapshot =
						(cachedContentSnapshot + text).takeLast(CACHE_SNAPSHOT_MAX_CHARS)
				} catch (e: Exception) {
					log.error("Failed to append build output to session file", e)
				}
			}
		}
	}

	/**
	 * Returns the last [WINDOW_SIZE_CHARS] characters from the session file for the editor to
	 * display (e.g. initial view or after rotation). Returns empty string if no content.
	 */
	fun getWindowForEditor(): String =
		lock.withLock {
			readTailFromFile(sessionFile, WINDOW_SIZE_CHARS)
		}

	/**
	 * Returns the full build output from the session file. Used for [BuildOutputProvider.getBuildOutputContent]
	 * and share/copy. Returns empty string if no content. File I/O is performed on [Dispatchers.IO].
	 */
	suspend fun getFullContent(): String =
		withContext(Dispatchers.IO) {
			lock.withLock {
				if (!sessionFile.exists()) return@withContext ""
				try {
					sessionFile.readText()
				} catch (e: Exception) {
					log.error("Failed to read full build output from session file", e)
					""
				}
			}
		}

	/**
	 * Reads a range from the session file (for future scroll/windowed UI). [offset] and [length] are
	 * in characters; implementation reads the corresponding byte range and decodes.
	 */
	fun getRange(
		offset: Int,
		length: Int,
	): String =
		lock.withLock {
			if (!sessionFile.exists()) return ""
			try {
				val content = sessionFile.readText()
				val start = max(0, offset).coerceAtMost(content.length)
				val end = (start + length).coerceAtMost(content.length)
				content.substring(start, end)
			} catch (e: Exception) {
				log.error("Failed to read range from build output session file", e)
				""
			}
		}

	/**
	 * Clears the session: deletes the session file and resets state. Call when a new build starts.
	 */
	fun clear() {
		lock.withLock {
			// Queued text is the finished build's; dropping it here, and bumping the generation for
			// the batch that may already be in flight, keeps the two sessions out of one file.
			sessionGeneration++
			while (pendingOutput.tryReceive().isSuccess) {
				// Discarded: this text belongs to the session being cleared.
			}
			cachedContentSnapshot = ""
			try {
				if (sessionFile.exists()) {
					sessionFile.delete()
				}
			} catch (e: Exception) {
				log.error("Failed to delete build output session file", e)
			}
		}
	}

	companion object {
		// Must mirror formatLinePrefix exactly; the round-trip is covered by BuildOutputFilterTest.
		// Anchored to line start so timestamp-shaped text inside a message is never stripped.
		private val PREFIX_REGEX =
			Regex("""^(\[\d{2}:\d{2}:\d{2}\.\d{3}\] )(\u0394\d+ms\s+)""")

		private val PREFIX_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

		/**
		 * Formats the timing prefix written before every build output line:
		 * `[HH:mm:ss.SSS] \u0394Nms `.
		 */
		fun formatLinePrefix(
			nowMs: Long,
			stepDeltaMs: Long,
		): String {
			val time =
				PREFIX_TIME_FORMAT.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()))
			return String.format(
				Locale.US,
				"[%s] %-8s ",
				time,
				"\u0394${stepDeltaMs}ms",
			)
		}

		/** Rebuilds [line] with the timestamp and/or delta part of its prefix hidden. */
		fun formatLineForDisplay(
			line: String,
			showTimestamps: Boolean,
			showDeltas: Boolean,
		): String {
			if (showTimestamps && showDeltas) return line
			val match = PREFIX_REGEX.find(line) ?: return line
			val (timestamp, delta) = match.destructured
			return buildString {
				if (showTimestamps) append(timestamp)
				if (showDeltas) append(delta)
				append(line, match.value.length, line.length)
			}
		}

		/**
		 * Returns only the lines of [content] whose *displayed* form (per [showTimestamps] and
		 * [showDeltas]) contains [query] (case-insensitive), each terminated with a newline.
		 * Returns [content] unchanged when there is nothing to filter or strip.
		 */
		fun filterLines(
			content: String,
			query: String,
			showTimestamps: Boolean = true,
			showDeltas: Boolean = true,
		): String {
			if (content.isEmpty() || (query.isEmpty() && showTimestamps && showDeltas)) return content
			// Drop the trailing empty element lineSequence() yields for newline-terminated input,
			// otherwise every render would gain a blank line.
			val body = if (content.endsWith('\n')) content.substring(0, content.length - 1) else content
			return buildString {
				for (rawLine in body.lineSequence()) {
					val displayLine = formatLineForDisplay(rawLine, showTimestamps, showDeltas)
					if (query.isEmpty() || displayLine.contains(query, ignoreCase = true)) {
						append(displayLine).append('\n')
					}
				}
			}
		}

		/**
		 * The last [maxChars] characters of [text], started at a line boundary.
		 *
		 * A tail sliced at a character offset begins part-way through a line, and [PREFIX_REGEX] is
		 * anchored to the start of one, so that fragment keeps the timestamp every other line has
		 * stripped. Text short enough to survive whole keeps its real first line; a tail holding no
		 * newline at all is returned as it is, being better than nothing.
		 */
		internal fun tailFromLineStart(
			text: String,
			maxChars: Int,
		): String {
			if (text.length <= maxChars) return text
			val tail = text.takeLast(maxChars)
			val newline = tail.indexOf('\n')
			return if (newline == -1) tail else tail.substring(newline + 1)
		}

		/**
		 * Reads the last [maxChars] characters of [file], or `""` when it is missing or unreadable.
		 * Shared with [com.itsaky.androidide.api.BuildOutputProvider], which reads the same session
		 * file for consumers outside the editor UI.
		 */
		internal fun readTailFromFile(
			file: File,
			maxChars: Int,
		): String {
			if (!file.exists()) return ""
			try {
				RandomAccessFile(file, "r").use { raf ->
					val len = raf.length()
					if (len == 0L) return ""
					// UTF-8: up to 4 bytes per char; read enough bytes for maxChars, then decode and take last maxChars
					val maxBytes = minOf(len, maxChars * 4L)
					raf.seek(max(0, len - maxBytes))
					val bytes = ByteArray(maxBytes.toInt())
					raf.readFully(bytes)
					val decoded = String(bytes, Charsets.UTF_8)
					return tailFromLineStart(decoded, maxChars)
				}
			} catch (e: Exception) {
				log.error("Failed to read tail from build output session file", e)
				return ""
			}
		}

		/** Name of the on-disk build output session file, shared with [com.itsaky.androidide.api.BuildOutputProvider]. */
		internal const val SESSION_FILE_NAME = "build_output_session.txt"
		private const val WINDOW_SIZE_CHARS = 512 * 1024

		/** Max length of [cachedContentSnapshot] to bound memory. */
		private const val CACHE_SNAPSHOT_MAX_CHARS = WINDOW_SIZE_CHARS
		private val log = org.slf4j.LoggerFactory.getLogger(BuildOutputViewModel::class.java)
	}
}
