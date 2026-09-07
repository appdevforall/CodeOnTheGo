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

import android.content.Context
import android.os.SystemClock
import androidx.annotation.UiThread

/**
 * Reads the watchers' buffers into a [MetricsCsv.Snapshot].
 *
 * Deliberately free of the carousel. This began on MetricsCarouselController, which bailed when the
 * carousel was unbound -- fine for a button on the carousel, wrong for everything else that wants
 * the file: the feedback FAB can be tapped with the strip closed (ADFA-5534), and a crash report is
 * assembled with no UI at all (ADFA-5526).
 *
 * All of it must be read on the UI thread; formatting and writing must not be.
 */
object MetricsSnapshotAssembler {
	/**
	 * The watchers' current buffers, as the export format's view of them.
	 *
	 * Rows come from the memory watcher: it is the only one always recording, the network watcher
	 * stops for good on a device whose counters are unsupported, and a power source can be missing.
	 * The other series are read at the same index -- the watchers share an interval, are started
	 * together and are cleared together -- and each carries its own sample times, so a series that
	 * was not recording leaves empty cells rather than zeros.
	 *
	 * @param context resolves an annotation's label, which a build outcome carries as a string id
	 *   so its marker follows the system language.
	 */
	@UiThread
	fun assemble(
		context: Context,
		memory: MemoryUsageWatcher,
		network: NetworkUsageWatcher,
		power: PowerUsageWatcher,
		annotations: MetricsAnnotationStore?,
	): MetricsCsv.Snapshot {
		val memoryTimes = memory.sampleTimes()
		val networkTimes = network.sampleTimes()
		val powerTimes = power.sampleTimes()
		val networkUsage = network.getUsage()
		val powerUsage = power.getUsage()

		return MetricsCsv.Snapshot(
			rowTimes = memoryTimes,
			memory =
				memory.getMemoryUsages().associate { process ->
					process.pname to
						MetricsCsv.Series(
							times = memoryTimes,
							values = process.usageHistory.toLongArray(),
							since = process.watchedSinceMillis,
						)
				},
			networkReceived = MetricsCsv.Series(networkTimes, networkUsage.received),
			networkTransmitted = MetricsCsv.Series(networkTimes, networkUsage.transmitted),
			temperature = MetricsCsv.Series(powerTimes, powerUsage.temperatureMilliCelsius),
			power = MetricsCsv.Series(powerTimes, powerUsage.powerMicroWatts),
			thermal = MetricsCsv.Series(powerTimes, powerUsage.thermalStatus),
			annotations = markers(context, annotations),
		)
	}

	/**
	 * The annotations, with their times moved onto the clock the samples carry.
	 *
	 * The store records on the monotonic clock and the samples on the wall clock, and the two are
	 * read here as close together as they can be so the offset between them is the right one.
	 */
	private fun markers(
		context: Context,
		annotations: MetricsAnnotationStore?,
	): List<MetricsCsv.Marker> {
		val store = annotations ?: return emptyList()
		val nowEpoch = System.currentTimeMillis()
		val nowMonotonic = SystemClock.elapsedRealtime()
		return store.allAnnotations().map { annotation ->
			MetricsCsv.Marker(
				atMillis = MetricsCsv.epochFor(annotation.atMillis, nowEpoch, nowMonotonic),
				label = annotation.kind.labelRes?.let(context::getString) ?: annotation.label,
				kind = annotation.kind.name,
			)
		}
	}
}
