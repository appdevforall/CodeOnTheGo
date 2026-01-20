package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.models.LogLine
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.LOG_FREQUENCY
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.MAX_CHUNK_SIZE
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.MAX_LINE_COUNT
import com.itsaky.androidide.viewmodel.LogViewModel.Companion.TRIM_ON_LINE_COUNT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
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
		 * The number of log events that are replayed to consumers.
		 */
		const val EVENT_REPLAY_COUNT = TRIM_ON_LINE_COUNT
	}

	sealed interface UiEvent {
		data class Append(
			val text: String,
		) : UiEvent
	}

	private val logs =
		MutableSharedFlow<String>(
			replay = EVENT_REPLAY_COUNT,
			extraBufferCapacity = EVENT_REPLAY_COUNT / 3,
			onBufferOverflow = BufferOverflow.DROP_OLDEST,
		)

	@OptIn(FlowPreview::class)
	val uiEvents: SharedFlow<UiEvent> =
		logs
			.map { if (it.endsWith("\n")) it else "$it\n" }
			.chunkedBySizeOrTime(MAX_CHUNK_SIZE, LOG_FREQUENCY)
			.map<String, UiEvent> { UiEvent.Append(it) }
			.shareIn(
				scope = viewModelScope,
				started = SharingStarted.Eagerly,
				replay = EVENT_REPLAY_COUNT,
			)

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
		val lineString =
			if (simpleFormattingEnabled) {
				line.toSimpleString()
			} else {
				line.toString()
			}

		line.recycle()
		submit(lineString)
	}

	/**
	 * Submit a log line.
	 *
	 * @param line The log line to submit.
	 */
	fun submit(line: String) {
		logs.tryEmit(line)
	}
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
