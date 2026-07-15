package com.itsaky.androidide.services.builder

import kotlin.math.min

/**
 * A balanced strategy for tuning Gradle builds.
 *
 * @author Akash Yadav
 */
object BalancedStrategy : GradleTuningStrategy {
	const val GRADLE_MEM_TO_XMX_FACTOR = 0.35
	const val GRADLE_METASPACE_MB = 192
	const val GRADLE_CODE_CACHE_MB = 128

	const val GRADLE_MEM_PER_WORKER = 512
	const val GRADLE_WORKERS_MAX = 3

	const val GRADLE_VFS_WATCH_MEMORY_REQUIRED_MB = 5 * 1024 // 5GB

	const val KOTLIN_METASPACE_MB = 128
	const val KOTLIN_CODE_CACHE_MB = 128

	const val AAPT2_MIN_THREADS = 2
	const val AAPT2_MAX_THREADS = 3

	override val name = "balanced"

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
		val maxWorkers = min(GRADLE_WORKERS_MAX, workersHardCap)
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
				parallel = maxWorkers >= GRADLE_WORKERS_MAX,
				configureOnDemand = true,
				caching = true,
				vfsWatch = device.mem.totalMemMb >= GRADLE_VFS_WATCH_MEMORY_REQUIRED_MB,
				configurationCache = false,
			)

		val kotlinXmx = (gradleXmx * 0.5).toInt()
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
