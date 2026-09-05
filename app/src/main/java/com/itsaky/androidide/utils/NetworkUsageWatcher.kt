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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

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
class NetworkUsageWatcher(
	updateInterval: Long = DEFAULT_UPDATE_INTERVAL,
	private val uid: Int = Process.myUid(),
	private val readRxBytes: (Int) -> Long = TrafficStats::getUidRxBytes,
	private val readTxBytes: (Int) -> Long = TrafficStats::getUidTxBytes,
) {
	@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
	private val coroutineDispatcher = newSingleThreadContext("NetworkUsageWatcher")
	private val coroutineScope = CoroutineScope(coroutineDispatcher)
	private val watching = AtomicBoolean(false)

	/**
	 * Milliseconds between samples. Changing it clears the history, for the reason given on
	 * [MemoryUsageWatcher.updateInterval].
	 */
	var updateInterval: Long = updateInterval
		set(value) {
			if (field == value) {
				return
			}
			field = value
			clearHistory()
		}

	/** Guards the two ring buffers: the sampler writes them, the UI thread snapshots them. */
	private val historyLock = Any()

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
	var listener: NetworkUsageListener? = null

	/**
	 * A snapshot of the sampled history, oldest first. Safe to call from any thread at any time;
	 * before the first sample every entry is zero.
	 *
	 * The arrays are copies. Handing out the live ring buffers would let the caller read them while
	 * the sampler thread is midway through appending, and the chart renderer reads all 30 entries.
	 */
	fun getUsage(): NetworkUsage =
		synchronized(historyLock) {
			NetworkUsage(received.snapshot(), transmitted.snapshot())
		}

	/**
	 * Discards every recorded sample and drops the cumulative baseline, so the next sample
	 * re-establishes it rather than reporting everything since the last one as one huge delta.
	 */
	fun clearHistory() {
		synchronized(historyLock) {
			received.clear()
			transmitted.clear()
			lastRx = null
			lastTx = null
		}
	}

	fun startWatching() {
		if (isWatching) {
			log.warn("Network usage is already being watched")
			return
		}

		watching.set(true)

		coroutineScope.launch(context = SupervisorJob() + coroutineDispatcher) {
			while (isWatching) {
				sampleOnce()

				listener?.also { listener ->
					val usage = getUsage()
					withContext(Dispatchers.Main.immediate) {
						listener.onNetworkUsageChanged(usage)
					}
				}

				delay(updateInterval)
			}
		}
	}

	fun stopWatching() {
		watching.set(false)
		coroutineScope.cancelIfActive("Cancellation requested")
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

		synchronized(historyLock) {
			record(received, previous = lastRx, current = rx)
			record(transmitted, previous = lastTx, current = tx)
		}

		lastRx = rx
		lastTx = tx
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
	 */
	data class NetworkUsage(
		val received: LongArray,
		val transmitted: LongArray,
	) {
		override fun equals(other: Any?): Boolean =
			this === other ||
				(
					other is NetworkUsage &&
						received.contentEquals(other.received) &&
						transmitted.contentEquals(other.transmitted)
				)

		override fun hashCode(): Int = 31 * received.contentHashCode() + transmitted.contentHashCode()
	}

	fun interface NetworkUsageListener {
		fun onNetworkUsageChanged(usage: NetworkUsage)
	}

	companion object {
		/**
		 * Samples retained per series (ADFA-5486). The span this covers depends on the interval:
		 * under three hours at one second, about seventeen minutes at the 0.1s minimum. 80KB of
		 * longs per series, so the cost is in drawing rather than holding -- see
		 * MetricsChartRenderer, which shows a window of this rather than all of it.
		 */
		const val MAX_USAGE_ENTRIES = 10000
		const val DEFAULT_UPDATE_INTERVAL = 1000L

		/** [TrafficStats.UNSUPPORTED] widened to [Long], which is what the getters return. */
		private const val UNSUPPORTED = TrafficStats.UNSUPPORTED.toLong()

		private val log = LoggerFactory.getLogger(NetworkUsageWatcher::class.java)
	}
}

/**
 * Copies this ring buffer into a plain array in logical order, oldest first.
 */
private fun ShiftedLongArray.snapshot(): LongArray = LongArray(size) { this[it] }
