package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import com.itsaky.androidide.logs.LogBuffer
import com.itsaky.androidide.models.LogFilter
import com.itsaky.androidide.models.LogLine
import com.itsaky.androidide.utils.ILogger
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.LOG_FREQUENCY
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.MAX_CHUNK_SIZE
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.MAX_LINE_COUNT
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.TRIM_ON_LINE_COUNT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * @author Akash Yadav
 */
abstract class LogViewModel : ViewModel() {
	companion object {
		/** The maximum number of characters to append to the editor in case of huge log texts. */
		const val MAX_CHUNK_SIZE = 10000

		/**
		 * The time duration, in milliseconds which is used to determine whether logs are too frequent
		 * or not. If the logs are produced within this time duration, they are considered as too
		 * frequent. In this case, the logs are cached and appended in chunks of [MAX_CHUNK_SIZE]
		 * characters in size.
		 */
		val LOG_FREQUENCY = 50L.milliseconds

		/**
		 * Trim the logs when the number of lines reaches this value. Only [MAX_LINE_COUNT]
		 * number of lines are kept in the logs.
		 */
		const val TRIM_ON_LINE_COUNT = 5000

		/**
		 * The maximum number of lines that are shown in the log view. This value must be less than
		 * [TRIM_ON_LINE_COUNT] by a difference of [LOG_FREQUENCY] or preferably, more.
		 */
		const val MAX_LINE_COUNT = TRIM_ON_LINE_COUNT - 300

		/**
		 * The number of live log events that may be buffered for a slow collector.
		 */
		const val EVENT_BUFFER_COUNT = TRIM_ON_LINE_COUNT
	}

	sealed interface UiEvent {
		/** Replace the entire log view content. */
		data class SetText(
			val text: String,
		) : UiEvent

		data class Append(
			val text: String,
		) : UiEvent
	}

	private val buffer = LogBuffer(TRIM_ON_LINE_COUNT, MAX_LINE_COUNT)

	private val liveEntries =
		MutableSharedFlow<LogBuffer.Entry>(
			replay = EVENT_BUFFER_COUNT,
			extraBufferCapacity = EVENT_BUFFER_COUNT,
			onBufferOverflow = BufferOverflow.DROP_OLDEST,
		)

	private val _filter = MutableStateFlow(LogFilter.NONE)
	val filter: StateFlow<LogFilter> = _filter.asStateFlow()

	// Bumped when the buffer is cleared, to force a re-render without a filter change
	private val generation = MutableStateFlow(0)

	/**
	 * The log view content as UI events: on every (re)collection or filter change, a
	 * [UiEvent.SetText] snapshot of the retained history filtered by the current [filter],
	 * followed by [UiEvent.Append]s for matching live lines. The snapshot is taken after
	 * subscribing to the live stream and stitched by sequence number, so no line is
	 * missed or duplicated in between.
	 */
	@OptIn(ExperimentalCoroutinesApi::class)
	val uiEvents: Flow<UiEvent> =
		combine(_filter, generation) { filter, _ -> filter }
			.flatMapLatest { filter ->
				channelFlow {
					var snapshotSeq = 0L
					liveEntries
						.onSubscription {
							val (text, lastSeq) = buffer.snapshotFiltered(filter)
							snapshotSeq = lastSeq
							send(UiEvent.SetText(text))
						}.filter { entry ->
							entry.seq > snapshotSeq && filter.matches(entry.level, entry.text)
						}.map { entry -> entry.text }
						.chunkedBySizeOrTime(MAX_CHUNK_SIZE, LOG_FREQUENCY)
						.collect { chunk -> send(UiEvent.Append(chunk)) }
				}
			}.flowOn(Dispatchers.Default)

	/**
	 * Submit a log line.
	 *
	 * @param line The log line to submit.
	 * @param simpleFormattingEnabled Whether to use simple formatting or not.
	 */
	fun submit(
		line: LogLine,
		simpleFormattingEnabled: Boolean = false,
	) {
		// Copy what we need before recycling -- LogLine instances are pooled
		val level = line.level
		val lineString =
			if (simpleFormattingEnabled) {
				line.toSimpleString()
			} else {
				line.toString()
			}

		line.recycle()
		submit(level, lineString)
	}

	/**
	 * Submit a log line without level metadata.
	 *
	 * @param line The log line to submit.
	 */
	fun submit(line: String) {
		submit(level = null, line = line)
	}

	// Keeps buffer mutation and live emission atomic
	private val eventLock = Any()

	/**
	 * Submit a log line.
	 *
	 * @param level The severity of the line, or `null` if unknown.
	 * @param line The log line to submit.
	 */
	fun submit(
		level: ILogger.Level?,
		line: String,
	) {
		val text = if (line.endsWith("\n")) line else "$line\n"
		synchronized(eventLock) {
			val entry = buffer.append(level, text)
			liveEntries.tryEmit(entry)
		}
	}

	fun setFilter(filter: LogFilter) {
		_filter.value = filter
	}

	/** Clear the retained log history and re-render the (now empty) view. */
	fun clear() {
		synchronized(eventLock) {
			buffer.clear()
			generation.update { it + 1 }
		}
	}

	/** The full retained history, ignoring the active filter. */
	fun snapshotUnfiltered(): String = buffer.snapshotAll()
}

/**
 * Map this [Flow] such that it emits its contents if no new content arrives
 * within [maxDelay]. If the frequency of the contents is too high i.e.
 * new content arrives within [maxDelay], they are emitted as chunks of
 * [maxSize] size.
 *
 * @param maxSize The maximum size of each chunk.
 * @param maxDelay The maximum delay between two consecutive contents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<String>.chunkedBySizeOrTime(
	maxSize: Int,
	maxDelay: Duration,
): Flow<String> =
	channelFlow {
		val buffer = StringBuilder()
		val mutex = Mutex()

		suspend fun flushLocked() {
			if (buffer.isNotEmpty()) {
				send(buffer.toString())
				buffer.clear()
			}
		}

		val flusher =
			launch {
				while (isActive) {
					delay(maxDelay)
					mutex.withLock {
						flushLocked()
					}
				}
			}

		try {
			collect { line ->
				mutex.withLock {
					if (buffer.length + line.length > maxSize) {
						flushLocked()
					}

					buffer.append(line)
				}
			}
		} finally {
			flusher.cancel()
			mutex.withLock {
				flushLocked()
			}
		}
	}
