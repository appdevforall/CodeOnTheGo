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

package com.itsaky.androidide.utils

import android.os.Debug.MemoryInfo
import androidx.annotation.VisibleForTesting
import androidx.collection.IntObjectMap
import androidx.collection.MutableIntObjectMap
import com.itsaky.androidide.tasks.cancelIfActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Handles memory usage information of the IDE.
 *
 * @property updateInterval The interval at which to update the memory usage.
 * @property coroutineDispatcher Where sampling runs. Injectable so tests can drive it with virtual
 * time rather than waiting on a real clock.
 * @property mainDispatcher Where listeners are notified.
 * @author Akash Yadav
 */
class MemoryUsageWatcher
	@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
	constructor(
		updateInterval: Long = DEFAULT_UPDATE_INTERVAL,
		private val coroutineDispatcher: CoroutineContext = newSingleThreadContext("MemoryUsageWatcher"),
		private val mainDispatcher: CoroutineContext = Dispatchers.Main.immediate,
		private val nowMillis: () -> Long = System::currentTimeMillis,
		// Injectable for the same reason the other watchers' readers are: it is the one part of a
		// sample that needs a device. A factory rather than a reader, because which read is correct
		// depends on the process -- see [ProcessMemoryReaders] (ADFA-5574).
		private val readerFor: (Int) -> ProcessMemoryReader = ProcessMemoryReaders::chooseReader,
	) {
		/**
		 * Milliseconds between samples. Changing it clears the history: the chart reads a sample's
		 * age from its position, which assumes every sample is the same age apart, and a buffer
		 * holding samples taken at two rates would silently misdate all the older ones (ADFA-5486).
		 *
		 * Volatile: written on the UI thread and read on the watcher's own sampling thread.
		 * Without it the reader can go on seeing a stale value indefinitely.
		 */
		@Volatile
		var updateInterval: Long = MetricsSamplingRates.coerceToSafeRange(updateInterval)
			set(value) {
				val safe = MetricsSamplingRates.coerceToSafeRange(value)
				if (field == safe) {
					return
				}
				field = safe
				clearHistory()
			}

		private val coroutineScope = CoroutineScope(SupervisorJob() + coroutineDispatcher)

		/** The running sampling loop, so [stopWatching] can actually stop it. */
		private var samplingJob: Job? = null
		private val memoryUsage = ConcurrentHashMap<Int, ProcessMemoryInfo>()

		/**
		 * When each sample was taken, in the same order and at the same indices as the values.
		 *
		 * Recorded rather than reconstructed. The chart infers a sample's age from its position,
		 * which is close enough for placing a marker on a plot, but the exported metrics file states
		 * a time per row (ADFA-5531) and inference would be wrong three ways: the newest sample was
		 * taken up to an interval before the export, the loop delays *after* doing its work so the
		 * true period drifts past the nominal one, and sampling can stop and restart without the
		 * buffer being cleared.
		 *
		 * A zero means no sample was ever recorded at that index, which is what tells a blank cell
		 * apart from a measured zero.
		 */
		private val sampleTimes = MutableShiftedLongArray(MAX_USAGE_ENTRIES)

		/**
		 * Guards the per-process ring buffers, matching [NetworkUsageWatcher] and
		 * [PowerUsageWatcher]. The sampler appends to them; [clearHistory] wipes them from whatever
		 * thread changed the sampling rate.
		 */
		private val historyLock = Any()
		private val watching = AtomicBoolean(false)

		/**
		 * Set by [close] and never cleared. Without it a start after a terminal teardown would flip
		 * [isWatching] to true and launch into a cancelled scope, leaving the watcher reporting that
		 * it is sampling when no loop exists.
		 */
		private val closed = AtomicBoolean(false)

		/**
		 * Whether the memory usage watcher is watching processes for their memory usage.
		 */
		val isWatching: Boolean
			get() = watching.get()

		/**
		 * The listener to be notified when the memory usage of a process changes.
		 *
		 * Volatile: written on the UI thread and read on the watcher's own sampling thread.
		 * Without it the reader can go on seeing a stale value indefinitely.
		 */
		@Volatile
		var listener: MemoryUsageListener? = null

		companion object {
			/**
			 * Samples retained per series.
			 *
			 * An hour at [DEFAULT_UPDATE_INTERVAL], and the chart shows sixty of them at a time
			 * (ADFA-5486). It was 10,000, which is nearly three hours nobody was looking at -- and
			 * eleven buffers of that is 859KB held for the life of the process, doubled by the
			 * pre-allocated snapshot destinations [MetricsScratch] adds so a crash handler never has
			 * to allocate. At 3,600 the two together cost less than the one did (ADFA-5526).
			 *
			 * A count of samples, not a duration: at the fastest offered rate of 100ms it is six
			 * minutes rather than an hour.
			 */
			const val MAX_USAGE_ENTRIES = 3600
			const val DEFAULT_UPDATE_INTERVAL = 1000L
			private val log = LoggerFactory.getLogger(MemoryUsageWatcher::class.java)
		}

		/**
		 * Start watching processes for their memory usage.
		 */
		fun startWatching() {
			if (closed.get()) {
				log.warn("Memory usage watcher is closed and cannot be restarted")
				return
			}

			if (!watching.compareAndSet(false, true)) {
				log.warn("Processes are already being watched for memory usage")
				return
			}

			samplingJob =
				coroutineScope.launch {
					while (isWatching) {
						// A throw here used to end the coroutine while `watching` stayed true, so
						// every later startWatching() was refused as "already watching" and
						// sampling stopped for good. A sample is worth losing; the loop is not.
						runCatching {
							readUsages()

							// don't bother to update if no listeners are set
							listener?.also { listener ->
								val usages = MutableIntObjectMap<ProcessMemoryInfo>(memoryUsage.size)
								for ((pid, usage) in this@MemoryUsageWatcher.memoryUsage) {
									usages[pid] = usage
								}
								withContext(mainDispatcher) {
									listener.onMemoryUsageChanged(usages)
								}
							}
						}.onFailure { failure ->
							if (failure is CancellationException) {
								throw failure
							}
							log.error("Memory usage sampling failed; continuing", failure)
						}

						delay(updateInterval)
					}
				}
		}

		@VisibleForTesting
		internal fun readUsages() {
			if (memoryUsage.isEmpty()) {
				return
			}

			// Read every process first, append nothing yet. The reading is the slow part and must
			// not hold the lock; the append is the part a reader can see, and all of it -- the time
			// and every process's value -- has to land in one critical section. A reader that
			// caught the time appended but not the values got a file whose every row sat on its
			// neighbour's timestamp, which is the one thing a row of this file is for (ADFA-5531).
			val at = nowMillis()
			val pids = memoryUsage.keys.toIntArray()
			val sampled = ArrayList<Pair<ProcessMemoryInfo, Long>>(pids.size)
			pids.forEach { pid ->
				val proc =
					memoryUsage[pid] ?: run {
						log.warn("Process {} is not being watched, but readUsages() was called for the process", pid)
						return@forEach
					}

				// values are in kB, convert to bytes
				sampled += proc to readKb(proc) * 1024L
			}

			synchronized(historyLock) {
				// The entry goes in at the start of the array and the shift amount goes up by one,
				// which makes it the last element and the oldest the first -- so
				// _history[_history.size - 1] is always the newest. The shift is the array's start
				// index, wrapping back to 0 once it passes the end.
				sampleTimes[0] = at
				sampleTimes.shift(1)
				sampled.forEach { (proc, usageBytes) ->
					proc._history[0] = usageBytes
					proc._history.shift(1)
				}
			}
		}

		/**
		 * This process's footprint in kB, falling back to the reflective read if the cheap one
		 * fails.
		 *
		 * The fallback latches on the process, so a rollup that cannot be read -- the process gone,
		 * a permission this build does not have -- costs one failed attempt rather than one every
		 * second for the rest of the session.
		 */
		private fun readKb(proc: ProcessMemoryInfo): Int {
			val kb = proc.reader.totalKb(proc.pid, proc.memInfo)
			if (kb != ProcessMemoryReaders.UNAVAILABLE) {
				return kb
			}
			if (proc.reader !== DebugMemoryInfoReader) {
				ProcessMemoryReaders.logFallback(proc.pid)
				proc.reader = DebugMemoryInfoReader
				return proc.reader.totalKb(proc.pid, proc.memInfo)
			}
			return 0
		}

		/**
		 * Watches the memory usage of the given process.
		 *
		 * @param pid The process ID.
		 * @param pname The process name.
		 * @param unique Whether to unwatch the process with the same process name.
		 */
		fun watchProcess(
			pid: Int,
			pname: String,
			unique: Boolean = true,
		) {
			if (memoryUsage.containsKey(pid)) {
				log.warn("Process {} is already being watched", pid)
				return
			}

			if (unique) {
				// unwatch the process with the given process name
				unwatchProcess(pname)
			}

			memoryUsage[pid] =
				ProcessMemoryInfo(
					pid,
					pname,
					MutableShiftedLongArray(MAX_USAGE_ENTRIES),
					// A process can start being watched long after the others -- the Gradle daemon
					// appears when a build does -- and its buffer is zero-filled back to the start
					// of the session. Without this, the exported file could not tell those zeros
					// from a process that really was using no memory (ADFA-5531).
					watchedSinceMillis = nowMillis(),
				).also { it.reader = readerFor(pid) }
		}

		/**
		 * Discards every recorded sample, keeping the watched processes.
		 */
		fun clearHistory() {
			// Held while clearing because clear() is two writes -- fill the array, reset the shift --
			// and the sampler's append is another two. Interleaved, they leave the buffer's shift
			// pointing into data that is no longer there, and the chart plots a scrambled history.
			// The rate dialog changes the interval from the UI thread while the sampler is running,
			// so this is reachable, not theoretical.
			synchronized(historyLock) {
				memoryUsage.values.forEach { it._history.clear() }
				sampleTimes.clear()
			}
		}

		/**
		 * Every retained sample, with the times the samples were taken at.
		 *
		 * One lock around the whole read, and it has to be: asking for the times and the values
		 * separately let the sampler append between the two calls, which shifts every value one
		 * index against its timestamp and puts each row of the exported file on its neighbour's
		 * time (ADFA-5531). There is no accessor for the times alone, deliberately.
		 *
		 * A zero time at an index means nothing was ever sampled there -- the buffers are
		 * fixed-length and start, and are cleared, full of them. Copies, for the same reason the
		 * values have always been copied.
		 */
		fun history(): MemoryHistory =
			synchronized(historyLock) {
				MemoryHistory(
					times = sampleTimes.toLongArray(),
					processes =
						memoryUsage.values.map { proc ->
							ProcessHistory(
								pid = proc.pid,
								pname = proc.pname,
								usage = proc._history.toLongArray(),
								watchedSinceMillis = proc.watchedSinceMillis,
							)
						},
				)
			}

		/**
		 * [history], into destinations the caller owns (ADFA-5526).
		 *
		 * Processes beyond the destinations given are dropped rather than allocated for -- the caller
		 * sized itself for [MetricsCsv.MEMORY_COLUMNS], which is every process the chart can plot.
		 */
		fun copyHistoryInto(
			timesDest: LongArray,
			destinations: List<LongArray>,
		): MemoryHistory =
			synchronized(historyLock) {
				MemoryHistory(
					times = sampleTimes.copyInto(timesDest),
					processes =
						memoryUsage.values.take(destinations.size).mapIndexed { index, proc ->
							ProcessHistory(
								pid = proc.pid,
								pname = proc.pname,
								usage = proc._history.copyInto(destinations[index]),
								watchedSinceMillis = proc.watchedSinceMillis,
							)
						},
				)
			}

		/**
		 * Returns the memory usage of all the registered processes.
		 */
		fun getMemoryUsages(): Array<ProcessMemoryInfo> =
			synchronized(historyLock) {
				// Snapshots, not the live objects. The sampler's append is two writes and clear()
				// is another two, and a reader holding nothing could see an advanced shift against
				// an old value -- plotting a point one slot out of place, which is exactly the
				// scrambled history the lock's own doc says it prevents. NetworkUsageWatcher and
				// PowerUsageWatcher already hand out copies for this reason.
				Array(memoryUsage.size) { index -> memoryUsage.values.elementAt(index).snapshot() }
			}

		/**
		 * Returns the memory usage of the given process (in bytes).
		 */
		fun getMemoryUsage(processId: Int): ProcessMemoryInfo? = memoryUsage[processId]

		/**
		 * Removes the given process from the watch list.
		 */
		fun unwatchProcess(processId: Int) {
			memoryUsage.remove(processId)
		}

		/**
		 * Removes the process with the given process name from the watch list.
		 */
		fun unwatchProcess(procName: String) {
			memoryUsage.values.forEach {
				if (it.pname == procName) {
					memoryUsage.remove(it.pid)
				}
			}
		}

		/**
		 * Unwatches all the registered processes.
		 */
		fun unwatchAll() {
			memoryUsage.clear()
		}

		/**
		 * Stop watching processes for their memory usage.
		 */
		fun stopWatching(unwatchAll: Boolean = true) {
			if (unwatchAll) {
				unwatchAll()
			}
			watching.set(false)
			// Cancelled rather than left to notice the flag: the loop spends almost all its time in
			// delay(updateInterval), up to a minute at the slowest rate, so a stop followed by a
			// start inside that window would leave the old loop running alongside the new one.
			samplingJob?.cancel()
			samplingJob = null
		}

		/**
		 * Stops sampling and releases the sampling thread. The watcher cannot be started again.
		 *
		 * Separate from [stopWatching] because a watcher is stopped and restarted across the
		 * editor's lifecycle; only a terminal teardown should give up the thread, and
		 * `newSingleThreadContext` holds one until it is closed.
		 */
		fun close() {
			closed.set(true)
			stopWatching()
			listener = null
			coroutineScope.cancelIfActive("Watcher closed")
			(coroutineDispatcher as? ExecutorCoroutineDispatcher)?.close()
		}

		/**
		 * One process's retained samples, detached from the watcher.
		 *
		 * Deliberately not [ProcessMemoryInfo], which carries a MemoryInfo and a ring buffer of its
		 * own and is what [getMemoryUsages] allocates.
		 *
		 * @property usage The samples, oldest first, in bytes.
		 * @property watchedSinceMillis When this process started being watched. Its buffer reaches
		 * back to the start of the session however late in it the process appeared, and this is what
		 * tells those zeros from a measurement.
		 */
		class ProcessHistory(
			val pid: Int,
			val pname: String,
			val usage: LongArray,
			val watchedSinceMillis: Long,
		)

		/**
		 * Every watched process's samples and the times they were taken at, read together.
		 *
		 * @property times When each sample was taken, oldest first, as milliseconds since the epoch,
		 * parallel to every entry in [processes].
		 */
		class MemoryHistory(
			val times: LongArray,
			val processes: List<ProcessHistory>,
		)

		/**
		 * Registers a listener to be notified when the memory usage of a process changes.
		 */
		fun interface MemoryUsageListener {
			/**
			 * Called when the memory usage of a process changes.
			 *
			 * @param memoryUsage The memory usage of all the registered processes.
			 */
			fun onMemoryUsageChanged(memoryUsage: IntObjectMap<ProcessMemoryInfo>)
		}

		/**
		 * Represents the memory usage of a process.
		 *
		 * @property pid The process ID.
		 * @property memInfo The latest [MemoryInfo] object. Stored here to ensure that we only allocate
		 * a single [MemoryInfo] object for a process.
		 * @property usageHistory The memory usage history of the process.
		 */
		data class ProcessMemoryInfo(
			val pid: Int,
			val pname: String,
			internal val _history: MutableShiftedLongArray,
			/** When this process started being watched, as milliseconds since the epoch. */
			val watchedSinceMillis: Long = 0L,
		) {
			internal val memInfo: MemoryInfo = MemoryInfo()

			/**
			 * How this process's footprint is read, chosen once when it starts being watched.
			 *
			 * Per process rather than per sample: the choice needs a file-existence check, and a
			 * read that fails at runtime latches here so it is not retried every second.
			 */
			internal var reader: ProcessMemoryReader = DebugMemoryInfoReader

			val usageHistory: ShiftedLongArray
				get() = _history

			/**
			 * A copy of this process's history, safe to read while the sampler keeps appending.
			 *
			 * The MemoryInfo instance is shared deliberately: it is the sampler's scratch buffer
			 * for the next reading and no reader looks at it.
			 */
			internal fun snapshot(): ProcessMemoryInfo =
				// Every field, including watchedSinceMillis. Dropping it let it default to 0, which
				// reads as "watched since the epoch" -- so the guard that blanks a process's
				// zero-filled past never fired, and the Gradle daemon's buffer exported as
				// measured zeros from before it existed (ADFA-5531).
				ProcessMemoryInfo(pid, pname, _history.copy(), watchedSinceMillis)

			override fun equals(other: Any?): Boolean {
				if (this === other) return true
				if (other !is ProcessMemoryInfo) return false

				if (pid != other.pid) return false
				if (!_history.contentEquals(other._history)) return false

				return true
			}

			override fun hashCode(): Int {
				var result = pid
				result = 31 * result + _history.contentHashCode()
				return result
			}
		}
	}
