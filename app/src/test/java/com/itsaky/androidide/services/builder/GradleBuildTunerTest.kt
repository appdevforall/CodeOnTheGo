package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class GradleBuildTunerTest {
	companion object {
		private val LOW_MEM_DEVICE =
			DeviceProfile(
				totalRamMb = 2 * 1024,
				availRamMb = 768,
				lowRam = true,
				cpuCores = 4,
				bigCores = 2,
				thermalThrottled = false,
				storageFreeMb = 10 * 1024,
			)

		private val MID_PERF_DEVICE =
			DeviceProfile(
				totalRamMb = 4 * 1024,
				availRamMb = 2 * 1024,
				lowRam = false,
				cpuCores = 8,
				bigCores = 4,
				thermalThrottled = false,
				storageFreeMb = 10 * 1024,
			)

		private val HIGH_PERF_DEVICE =
			DeviceProfile(
				totalRamMb = 8 * 1024,
				availRamMb = 6 * 1024,
				lowRam = false,
				cpuCores = 8,
				bigCores = 4,
				thermalThrottled = false,
				storageFreeMb = 10 * 1024,
			)
	}

	@Test
	fun `low memory strategy is picked for low-memory device`() {
		val strategy =
			GradleBuildTuner.pickStrategy(LOW_MEM_DEVICE, thermalSafe = false, previousConfig = null)
		assertThat(strategy).isInstanceOf(LowMemoryStrategy::class.java)
	}

	@Test
	fun `balanced strategy is picked for mid device`() {
		val strategy =
			GradleBuildTuner.pickStrategy(
				MID_PERF_DEVICE,
				thermalSafe = false,
				previousConfig = null,
			)
		assertThat(strategy).isInstanceOf(BalancedStrategy::class.java)
	}

	@Test
	fun `thermal-safe strategy is picked for thermal-throttled mid device on request`() {
		val prevConfig =
			GradleBuildTuner
				.pickStrategy(MID_PERF_DEVICE, false, null)
				.tune(MID_PERF_DEVICE, BuildProfile(isDebugBuild = false))

		val strategy =
			GradleBuildTuner.pickStrategy(
				MID_PERF_DEVICE.copy(thermalThrottled = true),
				thermalSafe = false,
				previousConfig = prevConfig,
			)

		assertThat(strategy).isInstanceOf(ThermalSafeStrategy::class.java)
	}

	@Test
	fun `thermal-safe strategy is picked for mid device on request`() {
		val prevConfig =
			GradleBuildTuner
				.pickStrategy(MID_PERF_DEVICE, false, null)
				.tune(MID_PERF_DEVICE, BuildProfile(isDebugBuild = false))

		val strategy =
			GradleBuildTuner.pickStrategy(
				MID_PERF_DEVICE,
				thermalSafe = true, // request thermal-safe
				previousConfig = prevConfig,
			)

		assertThat(strategy).isInstanceOf(ThermalSafeStrategy::class.java)
	}

	@Test
	fun `high performance strategy is picked for high-performance device`() {
		val strategy =
			GradleBuildTuner.pickStrategy(
				HIGH_PERF_DEVICE,
				thermalSafe = false,
				previousConfig = null,
			)
		assertThat(strategy).isInstanceOf(HighPerformanceStrategy::class.java)
	}

	@Test
	fun `thermal-safe strategy is picked for thermal-throttled, high-performance device`() {
		val prevConfig =
			GradleBuildTuner
				.pickStrategy(HIGH_PERF_DEVICE, false, null)
				.tune(HIGH_PERF_DEVICE, BuildProfile(isDebugBuild = false))

		val strategy =
			GradleBuildTuner.pickStrategy(
				HIGH_PERF_DEVICE.copy(thermalThrottled = true),
				thermalSafe = false,
				previousConfig = prevConfig,
			)

		assertThat(strategy).isInstanceOf(ThermalSafeStrategy::class.java)
	}

	@Test
	fun `thermal-safe strategy is picked for high-performance device on request`() {
		val prevConfig =
			GradleBuildTuner
				.pickStrategy(HIGH_PERF_DEVICE, false, null)
				.tune(HIGH_PERF_DEVICE, BuildProfile(isDebugBuild = false))

		val strategy =
			GradleBuildTuner.pickStrategy(
				HIGH_PERF_DEVICE,
				thermalSafe = true, // request thermal-safe
				previousConfig = prevConfig,
			)

		assertThat(strategy).isInstanceOf(ThermalSafeStrategy::class.java)
	}
}
