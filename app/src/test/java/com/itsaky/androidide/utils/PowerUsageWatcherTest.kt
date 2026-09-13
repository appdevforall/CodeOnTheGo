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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.PowerUsageWatcher.BatteryState
import com.itsaky.androidide.utils.PowerUsageWatcher.PowerReading
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins what ADFA-5499 records per sample: temperature, instantaneous power and the throttling
 * level land on one shared sample grid, and a reading the device does not provide stays
 * distinguishable from a real zero.
 *
 * These drive [PowerUsageWatcher.sampleOnce] directly rather than starting the sampling loop, so
 * there is no waiting and no dependence on thread timing.
 */
@RunWith(RobolectricTestRunner::class)
class PowerUsageWatcherTest {
	/** Every watcher built here, so the sampling threads they allocate are released. */
	private val created = mutableListOf<PowerUsageWatcher>()

	@After
	fun tearDown() {
		created.forEach { it.close() }
		created.clear()
	}

	/** A watcher fed a scripted sequence of readings, advancing one step per sample. */
	private inner class Fixture(
		private val readings: List<PowerReading>,
	) {
		private var index = -1

		val watcher =
			PowerUsageWatcher(
				source = { readings[index.coerceIn(0, readings.lastIndex)] },
			).also { created += it }

		fun sample(count: Int) {
			repeat(count) {
				index++
				watcher.sampleOnce()
			}
		}
	}

	private fun reading(
		temperature: Long = 30_000L,
		power: Long = 1_000_000L,
		thermal: Int = 0,
		battery: BatteryState = BatteryState(levelPercent = 80, isCharging = false),
	) = PowerReading(temperature, power, thermal, battery)

	private fun LongArray.recent(count: Int): List<Long> = takeLast(count)

	@Test
	fun `every slot reads as absent before the first sample`() {
		val fixture = Fixture(listOf(reading()))

		val usage = fixture.watcher.getUsage()

		// Asserted per slot, not as a sum. This test used to check `sum() == 0`, which passed for
		// a reason that had nothing to do with absence: 3600 * Long.MIN_VALUE wraps to exactly 0,
		// so the assertion held whether the buffers were filled with the sentinel or with zeros --
		// and went on holding when the thermal series was filled with the wrong sentinel entirely.
		assertThat(usage.temperatureMilliCelsius).hasLength(PowerUsageWatcher.MAX_USAGE_ENTRIES)
		assertThat(usage.temperatureMilliCelsius.toSet()).containsExactly(PowerUsageWatcher.UNAVAILABLE)
		assertThat(usage.powerMicroWatts.toSet()).containsExactly(PowerUsageWatcher.UNAVAILABLE)
		// Its own sentinel, which is what every consumer and the CSV's `absent` use.
		assertThat(usage.thermalStatus.toSet()).containsExactly(PowerUsageWatcher.THERMAL_UNKNOWN.toLong())
	}

	@Test
	fun `records temperature, power and throttling level on one sample grid`() {
		val fixture =
			Fixture(
				listOf(
					reading(temperature = 30_000L, power = 1_000_000L, thermal = 0),
					reading(temperature = 31_500L, power = 4_500_000L, thermal = 2),
					reading(temperature = 32_000L, power = 2_250_000L, thermal = 2),
				),
			)

		fixture.sample(3)
		val usage = fixture.watcher.getUsage()

		// Index n of each array is the same instant, which is what lets the chart shade a run of
		// equal levels by sample index rather than by a separate timeline.
		assertThat(usage.temperatureMilliCelsius.recent(3)).containsExactly(30_000L, 31_500L, 32_000L).inOrder()
		assertThat(usage.powerMicroWatts.recent(3)).containsExactly(1_000_000L, 4_500_000L, 2_250_000L).inOrder()
		assertThat(usage.thermalStatus.recent(3)).containsExactly(0L, 2L, 2L).inOrder()
	}

	@Test
	fun `an unavailable reading is recorded as unavailable, not as zero`() {
		val fixture = Fixture(listOf(reading(temperature = PowerUsageWatcher.UNAVAILABLE, power = PowerUsageWatcher.UNAVAILABLE)))

		fixture.sample(1)
		val usage = fixture.watcher.getUsage()

		// A device with no readable current would otherwise plot a flat, believable 0 mW.
		assertThat(usage.temperatureMilliCelsius.last()).isEqualTo(PowerUsageWatcher.UNAVAILABLE)
		assertThat(usage.powerMicroWatts.last()).isEqualTo(PowerUsageWatcher.UNAVAILABLE)
	}

	@Test
	fun `the sign of the current is recorded, not interpreted`() {
		val fixture = Fixture(listOf(reading(power = -3_000_000L)))

		fixture.sample(1)

		// The watcher passes the platform's sign through. Deciding what it means -- and that the
		// chart plots the magnitude either way -- is the renderer's job.
		assertThat(
			fixture.watcher
				.getUsage()
				.powerMicroWatts
				.last(),
		).isEqualTo(-3_000_000L)
	}

	@Test
	fun `the latest battery state is exposed for the legend`() {
		val fixture =
			Fixture(
				listOf(
					reading(battery = BatteryState(levelPercent = 80, isCharging = false)),
					reading(battery = BatteryState(levelPercent = 79, isCharging = true)),
				),
			)

		fixture.sample(2)

		assertThat(fixture.watcher.latestBattery).isEqualTo(BatteryState(levelPercent = 79, isCharging = true))
	}

	@Test
	fun `the ring buffer keeps only the most recent samples`() {
		val capacity = PowerUsageWatcher.MAX_USAGE_ENTRIES
		val readings = List(capacity + 2) { reading(temperature = it.toLong()) }
		val fixture = Fixture(readings)

		fixture.sample(readings.size)
		val usage = fixture.watcher.getUsage()

		assertThat(usage.temperatureMilliCelsius).hasLength(capacity)
		assertThat(usage.temperatureMilliCelsius.last()).isEqualTo((readings.size - 1).toLong())
		assertThat(usage.temperatureMilliCelsius.first()).isEqualTo(2L)
	}

	@Test
	fun `changing the sampling interval clears the history`() {
		val fixture = Fixture(listOf(reading()))
		fixture.sample(5)

		fixture.watcher.updateInterval = 5_000L

		// Samples taken at two rates in one buffer would misdate the older ones.
		val usage = fixture.watcher.getUsage()
		assertThat(usage.temperatureMilliCelsius.sum()).isEqualTo(0L)
		assertThat(usage.powerMicroWatts.sum()).isEqualTo(0L)
	}

	@Test
	fun `getUsage returns a copy, not the live buffer`() {
		val fixture = Fixture(listOf(reading(temperature = 30_000L), reading(temperature = 40_000L)))

		fixture.sample(1)
		val first = fixture.watcher.getUsage()
		val asHandedOut = first.temperatureMilliCelsius.copyOf()
		fixture.sample(1)

		assertThat(first.temperatureMilliCelsius).isEqualTo(asHandedOut)
		assertThat(fixture.watcher.getUsage().temperatureMilliCelsius).isNotEqualTo(asHandedOut)
	}
}
