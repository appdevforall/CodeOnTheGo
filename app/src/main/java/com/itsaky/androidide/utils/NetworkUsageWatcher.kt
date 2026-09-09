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

import android.net.TrafficStats
import android.os.Process
import androidx.annotation.VisibleForTesting
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Samples this app's network traffic (ADFA-5489).
 *
 * Accounting is UID-level, not per socket: [TrafficStats.getUidRxBytes] and
 * [TrafficStats.getUidTxBytes] cover every process sharing the app's UID, which is what makes
 * Gradle's downloads show up here -- the Gradle Tooling and daemon processes share it. No socket
 * tagging is involved, so there is deliberately no per-feature breakdown.
 *
 * The platform counters are cumulative since boot, so what is recorded is the *delta* between
 * consecutive samples: bytes transferred during that interval. A sampler that reported the raw
 * counters would draw a monotonically rising line that says nothing about current activity.
 *
 * @param updateInterval Milliseconds between samples.
 * @param uid The UID to account for. Defaults to this process's own; injectable for tests.
 * @param readRxBytes Reads the cumulative received byte count. Injectable for tests.
 * @param readTxBytes Reads the cumulative transmitted byte count. Injectable for tests.
 */
class NetworkUsageWatcher
	@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
	constructor(
		updateInterval: Long = DEFAULT_UPDATE_INTERVAL,
		private val uid: Int = Process.myUid(),
		private val readRxBytes: (Int) -> Long = TrafficStats::getUidRxBytes,
		private val readTxBytes: (Int) -> Long = TrafficStats::getUidTxBytes,
		// Injectable so a test can drive the sampling loop on a virtual clock. Waiting on the wall
		// clock instead is what hung the test executor the first time this was attempted.
		private val coroutineDispatcher: CoroutineContext = newSingleThreadContext("NetworkUsageWatcher"),
		// Null means "the real main dispatcher", resolved where it is used rather than here:
		// touching Dispatchers.Main at construction throws in a plain JVM test, and most of these
		// tests never start the sampling loop at all.
		private val mainDispatcher: CoroutineContext? = null,
		private val nowMillis: () -> Long = System::currentTimeMillis,
	) {
		private val coroutineScope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
		private val watching = AtomicBoolean(false)

		/**
		 * Set by [close] and never cleared. Without it a start after a terminal teardown would flip
		 * [isWatching] to true and launch into a cancelled scope, leaving the watcher reporting that
		 * it is sampling when no loop exists.
		 */
		private val closed = AtomicBoolean(false)

		/** The running sampling loop, so [stopWatching] can actually stop it. */
		private var samplingJob: Job? = null

		/**
		 * Milliseconds between samples. Changing it clears the history, for the reason given on
		 * [MemoryUsageWatcher.updateInterval].
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

		/** Guards the two ring buffers: the sampler writes them, the UI thread snapshots them. */
		private val historyLock = Any()

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

		private val received = MutableShiftedLongArray(MAX_USAGE_ENTRIES)
		private val transmitted = MutableShiftedLongArray(MAX_USAGE_ENTRIES)

		/**
		 * The previous cumulative readings, or `null` before the first sample. The first sample
		 * establishes a baseline and contributes no delta -- the alternative would be a spike equal to
		 * everything the app had transferred since boot.
		 */
		private var lastRx: Long? = null
		private var lastTx: Long? = null

		/**
		 * Whether the platform reports traffic for this UID at all. Cleared permanently if a read comes
		 * back [TrafficStats.UNSUPPORTED], which some devices and emulators do.
		 */
		@Volatile
		var isSupported: Boolean = true
			private set

		val isWatching: Boolean
			get() = watching.get()

		/**
		 * Notified on the main thread after each sample.
		 */
		@Volatile
		var listener: NetworkUsageListener? = null

		/**
		 * A snapshot of the sampled history, oldest first. Safe to call from any thread at any time;
		 * before the first sample every entry is zero.
		 *
		 * The arrays are copies. Handing out the live ring buffers would let the caller read them while
		 * the sampler thread is midway through appending, and the chart renderer reads all 30 entries.
		 */
		fun getUsage(): NetworkUsage = copyUsageInto(LongArray(received.size), LongArray(transmitted.size), LongArray(sampleTimes.size))

		/**
		 * [getUsage], into destinations the caller owns (ADFA-5526).
		 *
		 * The times come back with the values because they are read in the same critical section:
		 * asking separately let a sample land between the calls and shifted every value one index
		 * against its timestamp (ADFA-5531).
		 */
		fun copyUsageInto(
			receivedDest: LongArray,
			transmittedDest: LongArray,
			timesDest: LongArray,
		): NetworkUsage =
			synchronized(historyLock) {
				NetworkUsage(
					received.copyInto(receivedDest),
					transmitted.copyInto(transmittedDest),
					sampleTimes.copyInto(timesDest),
				)
			}

		/**
		 * Discards every recorded sample and drops the cumulative baseline, so the next sample
		 * re-establishes it rather than reporting everything since the last one as one huge delta.
		 */
		fun clearHistory() {
			synchronized(historyLock) {
				received.clear()
				transmitted.clear()
				sampleTimes.clear()
				lastRx = null
				lastTx = null
			}
		}

		fun startWatching() {
			if (closed.get()) {
				log.warn("Network usage watcher is closed and cannot be restarted")
				return
			}

			if (!watching.compareAndSet(false, true)) {
				log.warn("Network usage is already being watched")
				return
			}

			samplingJob =
				coroutineScope.launch {
					while (isWatching) {
						// A throw here used to end the coroutine while `watching` stayed true, so every
						// later startWatching() was refused as "already watching" and sampling stopped
						// for good. A sample is worth losing; the loop is not.
						runCatching {
							sampleOnce()

							listener?.also { listener ->
								val usage = getUsage()
								withContext(mainDispatcher ?: Dispatchers.Main.immediate) {
									listener.onNetworkUsageChanged(usage)
								}
							}
						}.onFailure { failure ->
							if (failure is CancellationException) {
								throw failure
							}
							log.error("Network usage sampling failed; continuing", failure)
						}

						// A device whose counters are unsupported has nothing further to give, and
						// the loop was otherwise repainting three charts a second with data known
						// to be permanently zero. Clearing the flag too, so isWatching does not
						// claim a sampler that has stopped.
						if (!isSupported) {
							watching.set(false)
							break
						}

						delay(updateInterval)
					}
				}
		}

		/**
		 * Stops sampling. The watcher can be started again; [close] is what makes it unusable.
		 *
		 * The job is cancelled rather than left to notice the flag: it spends almost all its time in
		 * `delay(updateInterval)`, which is up to a minute at the slowest rate, so a stop followed by a
		 * start inside that window would leave the old loop running alongside the new one, both
		 * recording samples and notifying the chart.
		 */
		fun stopWatching() {
			watching.set(false)
			// Drop the cumulative baseline as well. Left set, the first sample after a resume
			// reports everything transferred while the watcher was stopped as a single interval --
			// background a Gradle download for three minutes and the chart reads hundreds of MB/s.
			// The next sample re-establishes it, which is what the null baseline means.
			synchronized(historyLock) {
				lastRx = null
				lastTx = null
			}
			samplingJob?.cancel()
			samplingJob = null
		}

		/**
		 * Stops sampling and releases the sampling thread. The watcher cannot be started again.
		 *
		 * Separate from [stopWatching] because a watcher is stopped and restarted across the editor's
		 * lifecycle; only a terminal teardown should give up the thread, and `newSingleThreadContext`
		 * holds one until it is closed.
		 */
		fun close() {
			closed.set(true)
			stopWatching()
			listener = null
			coroutineScope.cancelIfActive("Watcher closed")
			(coroutineDispatcher as? ExecutorCoroutineDispatcher)?.close()
		}

		/**
		 * Takes one sample. The sampling loop calls this once per [updateInterval]; tests call it
		 * directly so the delta accounting can be exercised without threads or waiting.
		 */
		@VisibleForTesting
		internal fun sampleOnce() {
			if (!isSupported) {
				return
			}

			val rx = readRxBytes(uid)
			val tx = readTxBytes(uid)

			if (rx == UNSUPPORTED || tx == UNSUPPORTED) {
				// Not transient: the platform either accounts for this UID or it does not.
				isSupported = false
				log.info("Network usage is unavailable on this device; the traffic chart will read zero")
				return
			}

			// One block, not two. Between them clearHistory() could null the baselines -- it runs
			// when the sampling rate changes, precisely so that no delta straddles the change --
			// and the second block then put the pre-reset values straight back, so the next
			// sample counted traffic from before the change.
			synchronized(historyLock) {
				sampleTimes[0] = nowMillis()
				sampleTimes.shift(1)
				record(received, previous = lastRx, current = rx)
				record(transmitted, previous = lastTx, current = tx)
				lastRx = rx
				lastTx = tx
			}
		}

		/**
		 * Appends the delta between [previous] and [current] to [history].
		 *
		 * A negative delta means the counter went backwards, which happens when it is reset -- the
		 * device rebooted, or the platform re-based its accounting. Treated as a fresh baseline (zero
		 * for this interval) rather than plotted as negative traffic.
		 */
		private fun record(
			history: MutableShiftedLongArray,
			previous: Long?,
			current: Long,
		) {
			val delta =
				when {
					previous == null -> 0L
					current < previous -> 0L
					else -> current - previous
				}

			// Newest entry goes in at index 0 and the shift makes it the last element, so
			// history[size - 1] is always the newest. Same convention as MemoryUsageWatcher.
			history[0] = delta
			history.shift(1)
		}

		/**
		 * Bytes transferred per sampling interval, oldest first.
		 *
		 * @property received Bytes received during each interval.
		 * @property transmitted Bytes transmitted during each interval.
		 * @property sampleTimes When each sample was taken, oldest first, as milliseconds since the
		 * epoch, parallel to the values. Read in the same critical section as them, because reading
		 * the two separately let the sampler append between the calls and shifted every value one
		 * index against its timestamp (ADFA-5531). A zero means nothing was ever sampled at that
		 * index -- the buffers are fixed-length and start, and are cleared, full of them.
		 *
		 * Required, with no empty default. A caller that omitted it produced a history whose every
		 * sample read as never-taken, which the chart cannot see -- it asks only how long ago a
		 * sample was -- but which silently emptied both of this watcher's columns in the CSV.
		 */
		data class NetworkUsage(
			val received: LongArray,
			val transmitted: LongArray,
			val sampleTimes: LongArray,
		) {
			override fun equals(other: Any?): Boolean =
				this === other ||
					(
						other is NetworkUsage &&
							received.contentEquals(other.received) &&
							transmitted.contentEquals(other.transmitted) &&
							sampleTimes.contentEquals(other.sampleTimes)
					)

			override fun hashCode(): Int {
				var result = received.contentHashCode()
				result = 31 * result + transmitted.contentHashCode()
				result = 31 * result + sampleTimes.contentHashCode()
				return result
			}
		}

		fun interface NetworkUsageListener {
			fun onNetworkUsageChanged(usage: NetworkUsage)
		}

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

			/** [TrafficStats.UNSUPPORTED] widened to [Long], which is what the getters return. */
			private const val UNSUPPORTED = TrafficStats.UNSUPPORTED.toLong()

			private val log = LoggerFactory.getLogger(NetworkUsageWatcher::class.java)
		}
	}
