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
import androidx.annotation.AnyThread

/**
 * Reads the watchers' buffers into a [MetricsCsv.Snapshot].
 *
 * Deliberately free of the carousel. This began on MetricsCarouselController, which bailed when the
 * carousel was unbound -- fine for a button on the carousel, wrong for everything else that wants
 * the file: the feedback FAB can be tapped with the strip closed (ADFA-5534), and a crash report is
 * assembled with no UI at all (ADFA-5526).
 *
 * Every read here takes the watcher's own history lock, so this is safe from any thread -- which it
 * has to be, because a crash arrives on whatever thread threw (ADFA-5526). Formatting and writing
 * are a different matter and must stay off the main thread.
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
	@AnyThread
	fun assemble(
		context: Context,
		memory: MemoryUsageWatcher,
		network: NetworkUsageWatcher,
		power: PowerUsageWatcher,
		annotations: MetricsAnnotationStore?,
	): MetricsCsv.Snapshot = assemble(context, memory, network, power, annotations, scratch = null)

	/**
	 * Assembles a snapshot, hands it to [block], and only then gives the scratch back.
	 *
	 * The snapshot points *into* the scratch, so the scratch cannot be released when this returns --
	 * it has to outlive whatever reads the snapshot, which is a file write. Scoping it to a block is
	 * how that is made hard to get wrong.
	 *
	 * Falls back to allocating when the scratch is already taken. A crash must not wait on an export,
	 * and two writers into one array is a scrambled file.
	 */
	@AnyThread
	fun <T> withSnapshot(
		context: Context,
		memory: MemoryUsageWatcher,
		network: NetworkUsageWatcher,
		power: PowerUsageWatcher,
		annotations: MetricsAnnotationStore?,
		block: (MetricsCsv.Snapshot) -> T,
	): T {
		val scratch = MetricsScratch.instance?.takeIf { it.claim() }
		return try {
			block(assemble(context, memory, network, power, annotations, scratch))
		} finally {
			scratch?.release()
		}
	}

	private fun assemble(
		context: Context,
		memory: MemoryUsageWatcher,
		network: NetworkUsageWatcher,
		power: PowerUsageWatcher,
		annotations: MetricsAnnotationStore?,
		scratch: MetricsScratch?,
	): MetricsCsv.Snapshot {
		// One call per watcher, not one per array. Each hands back its times and its values from a
		// single critical section, which is what keeps a row of the file a single moment: asking
		// separately let a sample land between the two calls, and every value came out one row off
		// its own timestamp (ADFA-5531).
		val memoryHistory =
			if (scratch == null) {
				memory.history()
			} else {
				memory.copyHistoryInto(scratch.memoryTimes, scratch.memoryValues)
			}
		val networkUsage =
			if (scratch == null) {
				network.getUsage()
			} else {
				network.copyUsageInto(scratch.networkReceived, scratch.networkTransmitted, scratch.networkTimes)
			}
		val powerUsage =
			if (scratch == null) {
				power.getUsage()
			} else {
				power.copyUsageInto(scratch.temperature, scratch.power, scratch.thermal, scratch.powerTimes)
			}

		return MetricsCsv.Snapshot(
			rowTimes = memoryHistory.times,
			memory =
				memoryHistory.processes.associate { process ->
					process.pname to
						MetricsCsv.Series(
							times = memoryHistory.times,
							values = process.usage,
							since = process.watchedSinceMillis,
						)
				},
			networkReceived = MetricsCsv.Series(networkUsage.sampleTimes, networkUsage.received),
			networkTransmitted = MetricsCsv.Series(networkUsage.sampleTimes, networkUsage.transmitted),
			temperature = MetricsCsv.Series(powerUsage.sampleTimes, powerUsage.temperatureMilliCelsius),
			power = MetricsCsv.Series(powerUsage.sampleTimes, powerUsage.powerMicroWatts),
			thermal = MetricsCsv.Series(powerUsage.sampleTimes, powerUsage.thermalStatus),
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
