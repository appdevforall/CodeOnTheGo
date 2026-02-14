package com.itsaky.androidide.services.builder

/**
 * Tuning configuration for Gradle builds.
 *
 * @property gradle The configuration for the Gradle daemon.
 * @property kotlin The configuration for the Kotlin compiler.
 * @property dex The configuration for D8/R8.
 * @property aapt2 The configuration for the AAPT2 tool.
 * @author Akash Yadav
 */
data class GradleTuningConfig(
	val gradle: GradleDaemonConfig,
	val kotlin: KotlinCompilerExecution,
	val dex: DexConfig,
	val aapt2: Aapt2Config,
)

/**
 * Configuration for the Gradle daemon.
 *
 * @property enabled Whether the daemon is enabled.
 * @property jvm The configuration for the JVM instance.
 * @property maxWorkers The maximum number of workers.
 * @property parallel Whether parallel mode is enabled.
 * @property caching Whether caching is enabled.
 * @property configureOnDemand Whether configure on demand is enabled.
 * @property vfsWatch Whether VFS watch is enabled.
 * @property configurationCache Whether configuration cache is enabled.
 */
data class GradleDaemonConfig(
	val enabled: Boolean,
	val jvm: JvmConfig,
	val maxWorkers: Int,
	val parallel: Boolean,
	val caching: Boolean,
	val configureOnDemand: Boolean,
	val vfsWatch: Boolean,
	val configurationCache: Boolean,
)

/**
 * Execution strategy for the Kotlin compiler.
 *
 * @property incremental Whether incremental compilation is enabled.
 */
sealed interface KotlinCompilerExecution {
	val incremental: Boolean

	/**
	 * In-process Kotlin compilation.
	 */
	data class InProcess(
		override val incremental: Boolean,
	) : KotlinCompilerExecution

	/**
	 * Daemon Kotlin compilation.
	 */
	data class Daemon(
		override val incremental: Boolean,
		val jvm: JvmConfig,
	) : KotlinCompilerExecution
}

/**
 * Configuration for a JVM instance.
 *
 * @property xmsMb The initial JVM heap size.
 * @property xmxMb The maximum JVM heap size.
 * @property maxMetaspaceSize The maximum size of the metaspace (class metadata cap).
 * @property reservedCodeCacheSize The size of the reserved code (JIT) cache.
 * @property useG1Gc Whether to use the G1 garbage collector.
 * @property heapDumpOnOutOfMemory Whether to dump the heap on out of memory.
 */
data class JvmConfig(
	val xmsMb: Int,
	val xmxMb: Int,
	val maxMetaspaceSize: Int,
	val reservedCodeCacheSize: Int,
	val useG1Gc: Boolean = true,
	val heapDumpOnOutOfMemory: Boolean = false,
)

/**
 * Configuration for D8/R8.
 *
 * @property enableR8 Whether R8 is enabled.
 */
data class DexConfig(
	val enableR8: Boolean,
)

/**
 * Configuration for the AAPT2 tool.
 *
 * @property enableDaemon Whether the daemon is enabled.
 * @property threadPoolSize The size of the thread pool.
 * @property enableResourceOptimizations Whether resource optimizations are enabled.
 */
data class Aapt2Config(
	val enableDaemon: Boolean,
	val threadPoolSize: Int,
	val enableResourceOptimizations: Boolean,
)
