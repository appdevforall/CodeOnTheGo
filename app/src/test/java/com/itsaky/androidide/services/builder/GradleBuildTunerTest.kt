package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class GradleBuildTunerTest {
	companion object {
		private val LOW_MEM_INFO =
			MemInfo(
				totalMemMb = 2 * 1024,
				availRamMb = 768,
				isLowMemDevice = true,
			)

		private val LOW_PERF_CPU =
			CpuTopology(
				primeCores = null,
				bigCores = 2,
				smallCores = 2,
				totalCores = 4,
			)

		private val MID_MEM_INFO =
			MemInfo(
				totalMemMb = 4 * 1024,
				availRamMb = 2 * 1024,
				isLowMemDevice = false,
			)

		private val MID_PERF_CPU =
			CpuTopology(
				primeCores = null,
				bigCores = 4,
				smallCores = 4,
				totalCores = 8,
			)

		private val HIGH_MEM_INFO =
			MemInfo(
				totalMemMb = 8 * 1024,
				availRamMb = 6 * 1024,
				isLowMemDevice = false,
			)

		private val HIGH_PERF_CPU =
			CpuTopology(
				primeCores = 2,
				bigCores = 2,
				smallCores = 4,
				totalCores = 8,
			)

		private val LOW_MEM_DEVICE =
			DeviceProfile(
				mem = LOW_MEM_INFO,
				cpu = LOW_PERF_CPU,
				thermal = ThermalState.NotThrottled,
				storageFreeMb = 10 * 1024,
			)

		private val MID_PERF_DEVICE =
			DeviceProfile(
				mem = MID_MEM_INFO,
				cpu = MID_PERF_CPU,
				thermal = ThermalState.NotThrottled,
				storageFreeMb = 10 * 1024,
			)

		private val HIGH_PERF_DEVICE =
			DeviceProfile(
				mem = HIGH_MEM_INFO,
				cpu = HIGH_PERF_CPU,
				thermal = ThermalState.NotThrottled,
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
				MID_PERF_DEVICE.copy(thermal = ThermalState.Throttled),
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
				HIGH_PERF_DEVICE.copy(thermal = ThermalState.Throttled),
				thermalSafe = false,
				previousConfig = prevConfig,
			)

		assertThat(strategy).isInstanceOf(ThermalSafeStrategy::class.java)
	}

	@Test
	fun `low memory tier uses short daemon idle timeout`() {
		val config = LowMemoryStrategy.tune(LOW_MEM_DEVICE, BuildProfile(isDebugBuild = true))
		assertThat(config.gradle.daemonIdleTimeoutMs)
			.isEqualTo(LowMemoryStrategy.GRADLE_DAEMON_IDLE_TIMEOUT_MS)
	}

	@Test
	fun `balanced tier uses mid daemon idle timeout`() {
		val config = BalancedStrategy.tune(MID_PERF_DEVICE, BuildProfile(isDebugBuild = true))
		assertThat(config.gradle.daemonIdleTimeoutMs)
			.isEqualTo(BalancedStrategy.GRADLE_DAEMON_IDLE_TIMEOUT_MS)
	}

	@Test
	fun `high performance tier uses generous daemon idle timeout`() {
		val config = HighPerformanceStrategy.tune(HIGH_PERF_DEVICE, BuildProfile(isDebugBuild = true))
		assertThat(config.gradle.daemonIdleTimeoutMs)
			.isEqualTo(HighPerformanceStrategy.GRADLE_DAEMON_IDLE_TIMEOUT_MS)
	}

	@Test
	fun `daemon idle timeout increases with memory tier`() {
		// Guard against tier inversion: less RAM must never keep an idle daemon longer.
		assertThat(LowMemoryStrategy.GRADLE_DAEMON_IDLE_TIMEOUT_MS)
			.isLessThan(BalancedStrategy.GRADLE_DAEMON_IDLE_TIMEOUT_MS)
		assertThat(BalancedStrategy.GRADLE_DAEMON_IDLE_TIMEOUT_MS)
			.isLessThan(HighPerformanceStrategy.GRADLE_DAEMON_IDLE_TIMEOUT_MS)
	}

	@Test
	fun `thermal-safe strategy preserves previous daemon idle timeout`() {
		val prevConfig = BalancedStrategy.tune(MID_PERF_DEVICE, BuildProfile(isDebugBuild = true))
		val thermalConfig =
			ThermalSafeStrategy(prevConfig)
				.tune(MID_PERF_DEVICE, BuildProfile(isDebugBuild = true))
		assertThat(thermalConfig.gradle.daemonIdleTimeoutMs)
			.isEqualTo(prevConfig.gradle.daemonIdleTimeoutMs)
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
