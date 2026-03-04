package com.itsaky.androidide.services.builder

/**
 * Strategy for tuning Gradle builds.
 *
 * @author Akash Yadav
 */
interface GradleTuningStrategy {
	companion object {
		const val GRADLE_WORKERS_MAX_DEFAULT = 2
	}

	/**
	 * Name of the strategy.
	 */
	val name: String

	/**
	 * Create a tuning configuration for the given device profile.
	 *
	 * @param device The device profile to tune for.
	 * @param build The build profile to tune for.
	 * @return The tuning configuration.
	 */
	fun tune(
		device: DeviceProfile,
		build: BuildProfile,
	): GradleTuningConfig
}
