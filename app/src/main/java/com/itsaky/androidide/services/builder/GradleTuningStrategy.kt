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
	 * @param device the device profile; its memory, core count and thermal state pick the numbers.
	 * @param build the build profile for the run being tuned; no strategy reads it yet.
	 * @return the daemon, JVM and worker settings to run this build with.
	 */
	fun tune(
		device: DeviceProfile,
		build: BuildProfile,
	): GradleTuningConfig
}
