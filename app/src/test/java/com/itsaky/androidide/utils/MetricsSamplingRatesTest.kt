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
import com.itsaky.androidide.app.configuration.CpuArch
import org.junit.Test

/**
 * Pins the sampling-rate policy of ADFA-5486: 0.1s is the floor on 64-bit hardware, 0.5s on 32-bit,
 * and a rate a device cannot use is still offered, marked unavailable, so the user can see what the
 * hardware is costing them.
 */
class MetricsSamplingRatesTest {
	@Test
	fun `64-bit devices may sample ten times a second`() {
		assertThat(MetricsSamplingRates.minimumIntervalMillis(CpuArch.AARCH64)).isEqualTo(100L)
		assertThat(MetricsSamplingRates.minimumIntervalMillis(CpuArch.X86_64)).isEqualTo(100L)
	}

	@Test
	fun `32-bit devices are held to twice a second`() {
		assertThat(MetricsSamplingRates.minimumIntervalMillis(CpuArch.ARM)).isEqualTo(500L)
		assertThat(MetricsSamplingRates.minimumIntervalMillis(CpuArch.X86)).isEqualTo(500L)
	}

	@Test
	fun `every rate is offered to both, with the fast ones unavailable on 32-bit`() {
		val on64 = MetricsSamplingRates.ratesFor(CpuArch.AARCH64)
		val on32 = MetricsSamplingRates.ratesFor(CpuArch.ARM)

		// The same list either way: a rate the device cannot use is shown and greyed, not hidden,
		// so the user knows what they are missing rather than assuming the IDE cannot go faster.
		assertThat(on32.map { it.intervalMillis }).isEqualTo(on64.map { it.intervalMillis })

		assertThat(on64.filter { !it.isAvailable }).isEmpty()
		assertThat(on32.filter { !it.isAvailable }.map { it.intervalMillis })
			.containsExactly(100L, 200L)
			.inOrder()
	}

	@Test
	fun `the offered range spans the ticket's 0_1 to 60 seconds`() {
		val intervals = MetricsSamplingRates.OFFERED_INTERVALS_MS.toList()

		assertThat(intervals.first()).isEqualTo(100L)
		assertThat(intervals.last()).isEqualTo(MetricsSamplingRates.MAX_INTERVAL_MS)
		assertThat(intervals).isInOrder()
	}

	@Test
	fun `an out-of-range interval is clamped to what the device supports`() {
		// Faster than the hardware allows.
		assertThat(MetricsSamplingRates.coerceToSupportedRange(50L, CpuArch.ARM)).isEqualTo(500L)
		assertThat(MetricsSamplingRates.coerceToSupportedRange(50L, CpuArch.AARCH64)).isEqualTo(100L)

		// Slower than the slowest offered.
		assertThat(MetricsSamplingRates.coerceToSupportedRange(120_000L, CpuArch.AARCH64))
			.isEqualTo(60_000L)

		// Already in range.
		assertThat(MetricsSamplingRates.coerceToSupportedRange(2_000L, CpuArch.ARM)).isEqualTo(2_000L)
	}

	@Test
	fun `architectures are classified by word size`() {
		assertThat(CpuArch.AARCH64.is64Bit).isTrue()
		assertThat(CpuArch.X86_64.is64Bit).isTrue()
		assertThat(CpuArch.ARM.is64Bit).isFalse()
		assertThat(CpuArch.X86.is64Bit).isFalse()
	}
}
