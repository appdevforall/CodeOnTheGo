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
 * Samples the device's temperature and power draw (ADFA-5499).
 *
 * What a normally-installed app can read is narrower than it sounds. Battery temperature and the
 * current/voltage pair behind power come from the battery, free of any permission. The per-zone CPU
 * and skin temperatures the platform itself can see need `android.permission.DEVICE_POWER`, which is
 * signature-level and cannot be granted to an installed app at all -- hence [PowerSource], so a
 * privileged build could supply better readings without the chart changing.
 *
 * Power is instantaneous rather than cumulative: a running total only ever rises and says nothing
 * about which piece of work cost anything, whereas power lines up with the spikes on the memory and
 * network pages.
 *
 * @param updateInterval Milliseconds between samples.
 * @param source Where readings come from. Injectable so tests need no device.
 */
class PowerUsageWatcher
	@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
	constructor(
		updateInterval: Long = DEFAULT_UPDATE_INTERVAL,
		private val source: PowerSource,
		private val coroutineDispatcher: CoroutineContext = newSingleThreadContext("PowerUsageWatcher"),
		private val mainDispatcher: CoroutineContext = Dispatchers.Main.immediate,
		private val nowMillis: () -> Long = System::currentTimeMillis,
	) {
		private val coroutineScope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
		private val watching = AtomicBoolean(false)

		/**
		 * Set by [close] and never cleared. Without it a start after a terminal teardown would flip
		 * [isWatching] to true and launch into a cancelled scope, leaving the watcher reporting
		 * that it is sampling when no loop exists -- and nothing ever retries.
		 */
		private val closed = AtomicBoolean(false)

		/** The running sampling loop, so [stopWatching] can actually stop it. */
		private var samplingJob: Job? = null

		/** Guards the ring buffers: the sampler writes them, the UI thread snapshots them. */
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
		 * apart from a measured zero. Zero is safe as the sentinel here, unlike the value series
		 * below: no real sample was taken at the epoch.
		 */
		private val sampleTimes = MutableShiftedLongArray(MAX_USAGE_ENTRIES)

		// Filled with UNAVAILABLE, not zero. A slot that has never been sampled is an absence, and
		// zero is a reading: a zero-filled prefix plotted a flat 0 C and 0 W line and presented it
		// as measurement, which then forced applyAxisRanges to special-case `!= 0L` -- discarding
		// a genuine freezing-battery sample along with the fake ones (ADFA-5499).
		private val temperature = MutableShiftedLongArray(MAX_USAGE_ENTRIES) { UNAVAILABLE }
		private val power = MutableShiftedLongArray(MAX_USAGE_ENTRIES) { UNAVAILABLE }

		/**
		 * The thermal throttling level at each sample, or [THERMAL_UNKNOWN].
		 *
		 * Kept per sample rather than as a separate timestamped log so the chart's shading lines up
		 * with the sample grid exactly: a shaded span is just a run of equal values here.
		 */
		private val thermal = MutableShiftedLongArray(MAX_USAGE_ENTRIES) { UNAVAILABLE }

		/**
		 * Milliseconds between samples. Changing it clears the history, for the reason given on
		 * [MemoryUsageWatcher.updateInterval].
		 *
		 * Volatile: written on the UI thread and read on the watcher's own sampling thread.
		 * Without it the reader can go on seeing a stale value indefinitely.
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

		/** The most recent battery reading, for the chart's legend. */
		@Volatile
		var latestBattery: BatteryState = BatteryState.UNKNOWN
			private set

		val isWatching: Boolean
			get() = watching.get()

		/** Notified on the main thread after each sample. */
		@Volatile
		var listener: PowerUsageListener? = null

		/**
		 * A snapshot of the sampled history, oldest first. The arrays are copies; handing out the
		 * live ring buffers would let a reader see them mid-append.
		 */
		fun getUsage(): PowerUsage =
			synchronized(historyLock) {
				PowerUsage(
					temperature.toLongArray(),
					power.toLongArray(),
					thermal.toLongArray(),
					sampleTimes.toLongArray(),
				)
			}

		fun clearHistory() {
			synchronized(historyLock) {
				sampleTimes.clear()
				temperature.clear(UNAVAILABLE)
				power.clear(UNAVAILABLE)
				thermal.clear(UNAVAILABLE)
			}
		}

		fun startWatching() {
			if (closed.get()) {
				log.warn("Power usage watcher is closed and cannot be restarted")
				return
			}

			if (!watching.compareAndSet(false, true)) {
				log.warn("Power usage is already being watched")
				return
			}

			samplingJob =
				coroutineScope.launch {
					while (isWatching) {
						runCatching {
							sampleOnce()

							listener?.also { listener ->
								val usage = getUsage()
								withContext(mainDispatcher) {
									listener.onPowerUsageChanged(usage)
								}
							}
						}.onFailure { failure ->
							if (failure is CancellationException) {
								throw failure
							}
							log.error("Power usage sampling failed; continuing", failure)
						}

						delay(updateInterval)
					}
				}
		}

		fun stopWatching() {
			watching.set(false)
			samplingJob?.cancel()
			samplingJob = null
		}

		/** Stops sampling and releases the sampling thread. The watcher cannot be started again. */
		fun close() {
			closed.set(true)
			stopWatching()
			listener = null
			coroutineScope.cancelIfActive("Watcher closed")
			(coroutineDispatcher as? ExecutorCoroutineDispatcher)?.close()
		}

		/**
		 * Takes one sample. The loop calls this once per [updateInterval]; tests call it directly.
		 */
		@VisibleForTesting
		internal fun sampleOnce() {
			val reading = source.read()
			latestBattery = reading.battery

			synchronized(historyLock) {
				append(sampleTimes, nowMillis())
				append(temperature, reading.temperatureMilliCelsius)
				append(power, reading.powerMicroWatts)
				append(thermal, reading.thermalStatus.toLong())
			}
		}

		private fun append(
			history: MutableShiftedLongArray,
			value: Long,
		) {
			// Newest entry goes in at index 0 and the shift makes it the last element, matching
			// MemoryUsageWatcher and NetworkUsageWatcher.
			history[0] = value
			history.shift(1)
		}

		/**
		 * One sample's worth of readings.
		 *
		 * @property temperatureMilliCelsius Battery temperature, or [UNAVAILABLE].
		 * @property powerMicroWatts Instantaneous draw, or [UNAVAILABLE]. Signed as the platform
		 * signs the battery current: positive while charging, negative while discharging. Recorded
		 * as read; the renderer decides how to plot it.
		 * @property thermalStatus The platform throttling level, or [THERMAL_UNKNOWN].
		 * @property battery Level and charging state, for the legend.
		 */
		data class PowerReading(
			val temperatureMilliCelsius: Long,
			val powerMicroWatts: Long,
			val thermalStatus: Int,
			val battery: BatteryState,
		)

		/**
		 * @property levelPercent Charge remaining, or -1 if unknown.
		 * @property isCharging Whether the battery is being charged.
		 */
		data class BatteryState(
			val levelPercent: Int,
			val isCharging: Boolean,
		) {
			companion object {
				val UNKNOWN = BatteryState(levelPercent = -1, isCharging = false)
			}
		}

		/**
		 * Where readings come from. An interface because the best available source depends on how
		 * the app is installed: a privileged build can read per-zone temperatures that an installed
		 * one cannot.
		 */
		fun interface PowerSource {
			fun read(): PowerReading
		}

		/**
		 * Sampled history, oldest first.
		 *
		 * @property temperatureMilliCelsius Battery temperature per sample.
		 * @property powerMicroWatts Instantaneous draw per sample.
		 * @property thermalStatus Throttling level per sample, for the chart's shading.
		 * @property sampleTimes When each sample was taken, oldest first, as milliseconds since the
		 * epoch, parallel to the values. Read in the same critical section as them, because reading
		 * the two separately let the sampler append between the calls and shifted every value one
		 * index against its timestamp (ADFA-5531). A zero means nothing was ever sampled at that
		 * index -- the buffers are fixed-length and start, and are cleared, full of them.
		 *
		 * Required, with no empty default. A caller that omitted it produced a history whose every
		 * sample read as never-taken, which the chart cannot see -- it asks only how long ago a
		 * sample was -- but which silently emptied every one of this watcher's columns in the CSV.
		 */
		data class PowerUsage(
			val temperatureMilliCelsius: LongArray,
			val powerMicroWatts: LongArray,
			val thermalStatus: LongArray,
			val sampleTimes: LongArray,
		) {
			override fun equals(other: Any?): Boolean =
				this === other ||
					(
						other is PowerUsage &&
							temperatureMilliCelsius.contentEquals(other.temperatureMilliCelsius) &&
							powerMicroWatts.contentEquals(other.powerMicroWatts) &&
							thermalStatus.contentEquals(other.thermalStatus) &&
							sampleTimes.contentEquals(other.sampleTimes)
					)

			override fun hashCode(): Int {
				var result = temperatureMilliCelsius.contentHashCode()
				result = 31 * result + powerMicroWatts.contentHashCode()
				result = 31 * result + thermalStatus.contentHashCode()
				result = 31 * result + sampleTimes.contentHashCode()
				return result
			}
		}

		fun interface PowerUsageListener {
			fun onPowerUsageChanged(usage: PowerUsage)
		}

		companion object {
			/** Samples retained per series, matching the other watchers. */
			const val MAX_USAGE_ENTRIES = 10000
			const val DEFAULT_UPDATE_INTERVAL = 1000L

			/** A reading the device does not provide. */
			const val UNAVAILABLE = Long.MIN_VALUE

			/** No throttling level could be read -- an API 28 device, or the call failed. */
			const val THERMAL_UNKNOWN = -1

			private val log = LoggerFactory.getLogger(PowerUsageWatcher::class.java)
		}
	}
