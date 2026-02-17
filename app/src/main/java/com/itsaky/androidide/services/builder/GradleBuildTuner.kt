package com.itsaky.androidide.services.builder

import androidx.annotation.VisibleForTesting
import org.slf4j.LoggerFactory

/** @author Akash Yadav */
object GradleBuildTuner {

	private val logger = LoggerFactory.getLogger(GradleBuildTuner::class.java)

	const val LOW_MEM_THRESHOLD_MB = 3 * 1024 // 3GB
	const val HIGH_PERF_MIN_MEM_MB = 6 * 1024 // 6GB
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
				device.mem.isLowMemDevice || device.mem.totalMemMb <= LOW_MEM_THRESHOLD_MB -> LowMemoryStrategy()
				(thermalSafe || device.isThermalThrottled) && previousConfig != null ->
					ThermalSafeStrategy(
						previousConfig,
					)

				device.mem.totalMemMb >= HIGH_PERF_MIN_MEM_MB && device.cpu.totalCores >= HIGH_PERF_MIN_CORE -> HighPerformanceStrategy()
				else -> BalancedStrategy()
			}
		return strategy
	}
}
