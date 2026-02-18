package com.itsaky.androidide.services.builder

import kotlin.math.min

/**
 * A balanced strategy for tuning Gradle builds.
 *
 * @author Akash Yadav
 */
class BalancedStrategy : GradleTuningStrategy {
	companion object {
		const val GRADLE_XMX_MIN_MB = 768
		const val GRADLE_XMX_TARGET_MB = 1024
		const val GRADLE_METASPACE_MB = 192
		const val GRADLE_CODE_CACHE_MB = 128

		const val GRADLE_MEM_PER_WORKER = 512
		const val GRADLE_WORKERS_MAX = 3

		const val GRADLE_VFS_WATCH_MEMORY_REQUIRED_MB = 5 * 1024 // 5GB

		const val KOTLIN_XMX_TARGET_MB = 512
		const val KOTLIN_METASPACE_MB = 128
		const val KOTLIN_CODE_CACHE_MB = 128

		const val AAPT2_MIN_THREADS = 2
		const val AAPT2_MAX_THREADS = 3
	}

	override val name = "balanced"

	override fun tune(
		device: DeviceProfile,
		build: BuildProfile,
	): GradleTuningConfig {
		val gradleXmx = GRADLE_XMX_TARGET_MB.coerceIn(GRADLE_XMX_MIN_MB, (device.mem.totalMemMb * 0.33).toInt())
		val workersMemBound = (device.mem.totalMemMb / GRADLE_MEM_PER_WORKER).toInt()
		val workersCpuBound = device.cpu.totalCores
		val workersHardCap = min(GradleTuningStrategy.GRADLE_WORKERS_MAX_DEFAULT, min(workersMemBound, workersCpuBound))
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

		val kotlinXmx = min(KOTLIN_XMX_TARGET_MB, (gradleXmx / 0.5).toInt())
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
				threadPoolSize = (device.cpu.totalCores / 2).coerceIn(AAPT2_MIN_THREADS, AAPT2_MAX_THREADS),
				enableResourceOptimizations = true,
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
