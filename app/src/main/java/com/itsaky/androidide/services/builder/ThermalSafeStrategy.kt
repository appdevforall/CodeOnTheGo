package com.itsaky.androidide.services.builder

import kotlin.math.max

/**
 * A thermal safe strategy for tuning Gradle builds.
 *
 * @author Akash Yadav
 */
class ThermalSafeStrategy(
	private val previousConfig: GradleTuningConfig,
) : GradleTuningStrategy {

	override val name = "thermal_safe"

	override fun tune(
		device: DeviceProfile,
		build: BuildProfile,
	): GradleTuningConfig =
		previousConfig.run {
			val newMaxWorkers = max(1, gradle.maxWorkers - 1)

			copy(
				strategyName = this@ThermalSafeStrategy.name,
				gradle =
					gradle.run {
						copy(
							jvm =
								jvm.run {
									val newXmxMb = (xmxMb * 0.85).toInt()
									copy(
										xmxMb = newXmxMb,
										xmsMb = newXmxMb / 2,
									)
								},
							maxWorkers = newMaxWorkers,
							parallel = newMaxWorkers >= 3,
							vfsWatch = false,
						)
					},
				kotlin =
					when (val k = kotlin) {
						is KotlinCompilerExecution.InProcess -> k
						is KotlinCompilerExecution.Daemon ->
							k.run {
								copy(
									jvm =
										jvm.run {
											val newXmxMb = (xmxMb * 0.8).toInt()
											copy(
												xmxMb = newXmxMb,
												xmsMb = newXmxMb / 2,
											)
										},
								)
							}
					},
				aapt2 = aapt2.copy(threadPoolSize = newMaxWorkers),
			)
		}
}
