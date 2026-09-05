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

import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider

/**
 * The sampling rates the metrics charts offer, and which of them a given device may use
 * (ADFA-5486).
 *
 * Sampling costs a `Debug.getMemoryInfo` call per watched process plus two `TrafficStats` reads,
 * every interval. At the fastest rate that is ten times a second, which on weak hardware is enough
 * to distort the very thing the chart is measuring. 32-bit devices are therefore held to a slower
 * floor than 64-bit ones.
 *
 * Rates a device cannot use are still listed, marked unavailable, rather than hidden -- a chooser
 * that silently omits them leaves the user wondering whether the IDE simply cannot sample faster.
 * [Rate.isAvailable] is what a chooser should grey out; [minimumIntervalMillis] is the floor it
 * enforces.
 */
object MetricsSamplingRates {
	/** Floor for a 64-bit device: ten samples a second. */
	const val MIN_INTERVAL_64_BIT_MS = 100L

	/** Floor for a 32-bit device: two samples a second. */
	const val MIN_INTERVAL_32_BIT_MS = 500L

	/** The slowest rate offered, from the ticket's 0.1s-to-60s range. */
	const val MAX_INTERVAL_MS = 60_000L

	/**
	 * Every rate the chooser offers, fastest first.
	 */
	val OFFERED_INTERVALS_MS =
		longArrayOf(100L, 200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)

	/**
	 * A rate as a chooser should present it.
	 *
	 * @property intervalMillis The sampling interval.
	 * @property isAvailable Whether this device may select it.
	 */
	data class Rate(
		val intervalMillis: Long,
		val isAvailable: Boolean,
	)

	/**
	 * The fastest interval [arch] may sample at.
	 */
	fun minimumIntervalMillis(arch: CpuArch): Long = if (arch.is64Bit) MIN_INTERVAL_64_BIT_MS else MIN_INTERVAL_32_BIT_MS

	/**
	 * The fastest interval this device may sample at.
	 *
	 * Keyed on the device's architecture rather than the build flavour: a 32-bit build of the IDE
	 * running on a 64-bit phone is still running on hardware that can afford the faster rate.
	 */
	fun minimumIntervalMillis(): Long = minimumIntervalMillis(IDEBuildConfigProvider.getInstance().deviceArch)

	/**
	 * Every offered rate, each marked with whether [arch] may select it.
	 */
	fun ratesFor(arch: CpuArch): List<Rate> {
		val minimum = minimumIntervalMillis(arch)
		return OFFERED_INTERVALS_MS.map { interval -> Rate(interval, isAvailable = interval >= minimum) }
	}

	/**
	 * Clamps [intervalMillis] into the range [arch] may use.
	 */
	fun coerceToSupportedRange(
		intervalMillis: Long,
		arch: CpuArch,
	): Long = intervalMillis.coerceIn(minimumIntervalMillis(arch), MAX_INTERVAL_MS)
}

/**
 * Whether this architecture is 64-bit.
 */
val CpuArch.is64Bit: Boolean
	get() =
		when (this) {
			CpuArch.AARCH64, CpuArch.X86_64 -> true
			CpuArch.ARM, CpuArch.X86 -> false
		}
