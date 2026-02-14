package com.itsaky.androidide.services.builder

import androidx.annotation.VisibleForTesting

/** @author Akash Yadav */
object GradleBuildTuner {
	const val LOW_RAM_THRESHOLD_MB = 3 * 1024 // 3GB
	const val HIGH_PERF_MIN_RAM_MB = 6 * 1024 // 6GB
	const val HIGH_PERF_MIN_CORE = 4

	/**
	 * Automatically tune the Gradle build for the given device and build
	 * profile.
	 *
	 * @param device The device profile to tune for.
	 * @param build The build profile to tune for.
	 * @param previousConfig The previous tuning configuration.
	 * @param thermalSafe Whether to use the thermal safe strategy.
	 */
	fun autoTune(
		device: DeviceProfile,
		build: BuildProfile,
		previousConfig: GradleTuningConfig? = null,
		thermalSafe: Boolean = false,
	): GradleTuningConfig {
		val strategy = pickStrategy(device, thermalSafe, previousConfig)
		return strategy.tune(device, build)
	}

	@VisibleForTesting
	internal fun pickStrategy(
		device: DeviceProfile,
		thermalSafe: Boolean,
		previousConfig: GradleTuningConfig?,
	): GradleTuningStrategy {
		val strategy =
			when {
				device.lowRam || device.totalRamMb <= LOW_RAM_THRESHOLD_MB -> LowMemoryStrategy()
				(thermalSafe || device.thermalThrottled) && previousConfig != null ->
					ThermalSafeStrategy(
						previousConfig,
					)

				device.totalRamMb >= HIGH_PERF_MIN_RAM_MB && device.cpuCores >= HIGH_PERF_MIN_CORE -> HighPerformanceStrategy()
				else -> BalancedStrategy()
			}
		return strategy
	}
}
