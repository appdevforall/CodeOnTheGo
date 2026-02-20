package com.itsaky.androidide.services.builder

import kotlin.math.min

/**
 * A low memory strategy for tuning Gradle builds.
 *
 * @author Akash Yadav
 */
object LowMemoryStrategy : GradleTuningStrategy {
	const val GRADLE_MEM_TO_XMX_FACTOR = 0.33
	const val GRADLE_METASPACE_MB = 192
	const val GRADLE_CODE_CACHE_MB = 128

	const val GRADLE_MEM_PER_WORKER = 512
	const val GRADLE_WORKERS_MAX = 2

	const val GRADLE_CACHING_STORAGE_REQUIRED_MB = 2048

	const val AAPT2_MIN_THREADS = 1
	const val AAPT2_MAX_THREADS = 2

	override val name = "low_memory"

	override fun tune(
		device: DeviceProfile,
		build: BuildProfile,
	): GradleTuningConfig {
		val gradleXmx = (device.mem.totalMemMb * GRADLE_MEM_TO_XMX_FACTOR).toInt()
		val gradleXms = gradleXmx / 2
		val workersMemBound = (device.mem.totalMemMb / GRADLE_MEM_PER_WORKER).toInt()
		val workersCpuBound = device.cpu.totalCores
		val workersHardCap =
			min(
				GradleTuningStrategy.GRADLE_WORKERS_MAX_DEFAULT,
				min(workersMemBound, workersCpuBound),
			)
		val gradleDaemon =
			GradleDaemonConfig(
				daemonEnabled = true,
				jvm =
					JvmConfig(
						xmxMb = gradleXmx,
						xmsMb = gradleXms,
						maxMetaspaceSizeMb = GRADLE_METASPACE_MB,
						reservedCodeCacheSizeMb = GRADLE_CODE_CACHE_MB,
						gcType = GcType.Serial,
					),
				maxWorkers = min(GRADLE_WORKERS_MAX, workersHardCap).coerceAtLeast(1),
				parallel = false,
				configureOnDemand = true,
				caching = device.storageFreeMb >= GRADLE_CACHING_STORAGE_REQUIRED_MB,
				vfsWatch = false,
				configurationCache = false,
			)

		val kotlinExec = KotlinCompilerExecution.InProcess(incremental = true)
		val aapt2 =
			Aapt2Config(
				enableDaemon = true,
				threadPoolSize = if (device.cpu.totalCores >= 6) AAPT2_MAX_THREADS else AAPT2_MIN_THREADS,
				enableResourceOptimizations = false,
			)

		return GradleTuningConfig(
			strategyName = name,
			gradle = gradleDaemon,
			kotlin = kotlinExec,
			aapt2 = aapt2,
		)
	}
}
