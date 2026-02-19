package com.itsaky.androidide.services.builder

import kotlin.math.min

/**
 * A high performance strategy for tuning Gradle builds.
 *
 * @author Akash Yadav
 */
object HighPerformanceStrategy : GradleTuningStrategy {
	const val GRADLE_MEM_TO_XMX_FACTOR = 0.4
	const val GRADLE_METASPACE_MB = 384
	const val GRADLE_CODE_CACHE_MB = 256

	const val GRADLE_MEM_PER_WORKER = 512
	const val GRADLE_CONF_CACHE_MEM_REQUIRED_MB = 6 * 1024 // 6GB

	const val KOTLIN_METASPACE_MB = 128
	const val KOTLIN_CODE_CACHE_MB = 128

	const val AAPT2_MIN_THREADS = 2
	const val AAPT2_MAX_THREADS = 3

	override val name = "high_performance"

	override fun tune(
		device: DeviceProfile,
		build: BuildProfile,
	): GradleTuningConfig {
		val gradleXmx = (device.mem.totalMemMb * GRADLE_MEM_TO_XMX_FACTOR).toInt()
		val workersMemBound = (device.mem.totalMemMb / GRADLE_MEM_PER_WORKER).toInt()
		val workersCpuBound = device.cpu.totalCores
		val workersHardCap =
			min(
				GradleTuningStrategy.GRADLE_WORKERS_MAX_DEFAULT,
				min(workersMemBound, workersCpuBound),
			)
		val maxWorkers = min(workersHardCap, device.cpu.totalCores - 1)
		val gradleDaemon =
			GradleDaemonConfig(
				daemonEnabled = true,
				jvm =
					JvmConfig(
						xmxMb = gradleXmx,
						xmsMb = gradleXmx / 2,
						maxMetaspaceSizeMb = GRADLE_METASPACE_MB,
						reservedCodeCacheSizeMb = GRADLE_CODE_CACHE_MB,
					),
				maxWorkers = maxWorkers,
				parallel = true,
				configureOnDemand = false,
				caching = true,
				vfsWatch = true,
				configurationCache = device.mem.totalMemMb >= GRADLE_CONF_CACHE_MEM_REQUIRED_MB,
			)

		val kotlinXmx = (gradleXmx * 0.7).toInt()
		val kotlinExec =
			KotlinCompilerExecution.Daemon(
				incremental = true,
				jvm =
					JvmConfig(
						xmxMb = kotlinXmx,
						xmsMb = kotlinXmx / 2,
						maxMetaspaceSizeMb = KOTLIN_METASPACE_MB,
						reservedCodeCacheSizeMb = KOTLIN_CODE_CACHE_MB,
					),
			)

		val aapt2 =
			Aapt2Config(
				enableDaemon = true,
				threadPoolSize =
					(device.cpu.totalCores / 2).coerceIn(
						AAPT2_MIN_THREADS,
						AAPT2_MAX_THREADS,
					),
				enableResourceOptimizations = true,
			)

		return GradleTuningConfig(
			strategyName = name,
			gradle = gradleDaemon,
			kotlin = kotlinExec,
			aapt2 = aapt2,
		)
	}
}
