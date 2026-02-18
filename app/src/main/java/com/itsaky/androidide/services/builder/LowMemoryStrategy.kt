package com.itsaky.androidide.services.builder

import kotlin.math.min

/**
 * A low memory strategy for tuning Gradle builds.
 *
 * @author Akash Yadav
 */
class LowMemoryStrategy : GradleTuningStrategy {
	companion object {
		const val GRADLE_XMX_MIN_MB = 384
		const val GRADLE_XMX_TARGET_MB = 512
		const val GRADLE_METASPACE_MB = 192
		const val GRADLE_CODE_CACHE_MB = 128

		const val GRADLE_MEM_PER_WORKER = 512
		const val GRADLE_WORKERS_MAX = 2

		const val GRADLE_CACHING_STORAGE_REQUIRED_MB = 2048

		const val AAPT2_MIN_THREADS = 1
		const val AAPT2_MAX_THREADS = 2
	}

	override val name = "low_memory"

	override fun tune(
		device: DeviceProfile,
		build: BuildProfile,
	): GradleTuningConfig {
		val memBound = (device.mem.totalMemMb * 0.33).toInt()
		val gradleXmx =
			if (memBound > GRADLE_XMX_MIN_MB) {
				GRADLE_XMX_TARGET_MB.coerceIn(GRADLE_XMX_MIN_MB, memBound)
			} else {
				// The device has very low memory (<=1GB)
				// In this case, GRADLE_XMX_MIN_MB might be too large
				// and the device may not be able to allocate such
				// amount of memory
				// fall back to using the memory-bound limit
				memBound
			}

		val gradleXms = gradleXmx / 2
		val workersMemBound = (device.mem.totalMemMb / GRADLE_MEM_PER_WORKER).toInt()
		val workersCpuBound = device.cpu.totalCores
		val workersHardCap = min(GradleTuningStrategy.GRADLE_WORKERS_MAX_DEFAULT, min(workersMemBound, workersCpuBound))
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
				maxWorkers = min(GRADLE_WORKERS_MAX, workersHardCap),
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

		val dex =
			DexConfig(
				enableR8 = !build.isDebugBuild,
			)

		return GradleTuningConfig(
			strategyName = name,
			gradle = gradleDaemon,
			kotlin = kotlinExec,
			dex = dex,
			aapt2 = aapt2,
		)
	}
}
