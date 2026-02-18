package com.itsaky.androidide.services.builder

import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.analytics.gradle.StrategySelectedMetric
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import org.slf4j.LoggerFactory

/** @author Akash Yadav */
object GradleBuildTuner {
	private val logger = LoggerFactory.getLogger(GradleBuildTuner::class.java)

	const val LOW_MEM_THRESHOLD_MB = 3 * 1024 // 3GB
	const val HIGH_PERF_MIN_MEM_MB = 6 * 1024 // 6GB
	const val HIGH_PERF_MIN_CORE = 4

	enum class SelectionReason(
		val label: String,
	) {
		/**
		 * The device has low memory, as reported by
		 * [isLowRamDevice][android.app.ActivityManager.isLowRamDevice].
		 */
		LowMemDevice("low_mem_device"),

		/** The device has memory below our [LOW_MEM_THRESHOLD_MB]. */
		LowMemThreshold("low_mem_threshold"),

		/**
		 * The device is thermal throttled and a previous tuning configuration was
		 * available.
		 */
		ThermalWithPrevious("thermal_with_previous"),

		/**
		 * The device is thermal throttled and no previous tuning configuration was
		 * available.
		 */
		ThermalWithoutPrevious("thermal_without_previous"),

		/**
		 * The device has enough memory and cores to run a high performance build,
		 * as determined by our [HIGH_PERF_MIN_MEM_MB] and [HIGH_PERF_MIN_CORE]
		 * thresholds.
		 */
		HighPerf("high_perf_mem"),

		/**
		 * The specs could not be classified into any of the above categories, so
		 * we use the fallback strategy.
		 */
		BalancedFallback("balanced_fallback"),
	}

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
		analyticsManager: IAnalyticsManager? = null,
		buildId: BuildId? = null,
	): GradleTuningConfig {
		val strategy =
			pickStrategy(
				device,
				thermalSafe,
				previousConfig,
				analyticsManager,
				buildId,
			)
		return strategy.tune(device, build)
	}

	@VisibleForTesting
	internal fun pickStrategy(
		device: DeviceProfile,
		thermalSafe: Boolean,
		previousConfig: GradleTuningConfig?,
		analyticsManager: IAnalyticsManager? = null,
		buildId: BuildId? = null,
	): GradleTuningStrategy {
		val isLowMemDevice = device.mem.isLowMemDevice
		val totalMemMb = device.mem.totalMemMb
		val totalCores = device.cpu.totalCores
		val isThermallyConstrained = thermalSafe || device.isThermalThrottled
		val hasPreviousConfig = previousConfig != null
		val meetsHighPerfMem = totalMemMb >= HIGH_PERF_MIN_MEM_MB
		val meetsHighPerfCores = totalCores >= HIGH_PERF_MIN_CORE

		logger.info(
			"Evaluating strategy selection: " +
				"isLowMemDevice={}, totalMemMb={}, totalCores={}, " +
				"thermalSafe={}, isThermalThrottled={}, previousConfigPresent={}",
			isLowMemDevice,
			totalMemMb,
			totalCores,
			thermalSafe,
			device.isThermalThrottled,
			hasPreviousConfig,
		)

		val (strategy, reason) =
			when {
				isLowMemDevice -> LowMemoryStrategy() to SelectionReason.LowMemDevice
				totalMemMb <= LOW_MEM_THRESHOLD_MB -> LowMemoryStrategy() to SelectionReason.LowMemThreshold

				isThermallyConstrained && hasPreviousConfig -> ThermalSafeStrategy(previousConfig) to SelectionReason.ThermalWithPrevious
				isThermallyConstrained && !hasPreviousConfig -> BalancedStrategy() to SelectionReason.ThermalWithoutPrevious

				meetsHighPerfMem && meetsHighPerfCores -> HighPerformanceStrategy() to SelectionReason.HighPerf

				else -> BalancedStrategy() to SelectionReason.BalancedFallback
			}

		logger.info("selected {} build strategy because: {}", strategy.name, reason.label)
		analyticsManager?.trackGradleStrategySelected(
			metric =
				StrategySelectedMetric(
					buildId = buildId ?: BuildId.Unknown,
					totalMemBucketed = getMemBucket(totalMemMb),
					totalCores = totalCores,
					isThermalThrottled = isThermallyConstrained,
					isLowMemDevice = isLowMemDevice,
					lowMemThresholdMb = LOW_MEM_THRESHOLD_MB,
					highPerfMinMemMb = HIGH_PERF_MIN_MEM_MB,
					highPerfMinCores = HIGH_PERF_MIN_CORE,
					hasPreviousConfig = hasPreviousConfig,
					previousStrategy = previousConfig?.strategyName,
					newStrategy = strategy,
					reason = reason,
				),
		)

		return strategy
	}

	private fun getMemBucket(memMb: Long): String =
		when {
			memMb < 2 * 1024 -> "<2GB"
			memMb < 4 * 1024 -> "2-4GB"
			memMb < 6 * 1024 -> "4-6GB"
			memMb < 8 * 1024 -> "6-8GB"
			memMb < 12 * 1024 -> "8-12GB"
			else -> "12GB+"
		}

	/**
	 * Convert the given tuning configuration to a Gradle build parameters.
	 *
	 * @param tuningConfig The tuning configuration to convert.
	 */
	fun toGradleBuildParams(tuningConfig: GradleTuningConfig): GradleBuildParams {
		val gradleArgs =
			buildList {
				val gradle = tuningConfig.gradle

				// Daemon
				if (!gradle.daemonEnabled) add("--no-daemon")

				// Worker count
				add("--max-workers=${gradle.maxWorkers}")

				// Parallel execution
				add(if (gradle.parallel) "--parallel" else "--no-parallel")

				// Build cache
				add(if (gradle.caching) "--build-cache" else "--no-build-cache")

				// Configure on demand
				add(if (gradle.configureOnDemand) "--configure-on-demand" else "--no-configure-on-demand")

				// Configuration cache
				add(if (gradle.configurationCache) "--configuration-cache" else "--no-configuration-cache")

				// VFS watch (file system watching)
				add(if (gradle.vfsWatch) "--watch-fs" else "--no-watch-fs")

				// Kotlin compiler strategy
				when (val kotlin = tuningConfig.kotlin) {
					is KotlinCompilerExecution.InProcess -> {
						add("-Pkotlin.compiler.execution.strategy=in-process")
						add("-Pkotlin.incremental=${kotlin.incremental}")
					}

					is KotlinCompilerExecution.Daemon -> {
						add("-Pkotlin.compiler.execution.strategy=daemon")
						add("-Pkotlin.incremental=${kotlin.incremental}")

						val daemonJvmArgs = toJvmArgs(kotlin.jvm)
						if (daemonJvmArgs.isNotEmpty()) {
							add("-Pkotlin.daemon.jvm.options=${daemonJvmArgs.joinToString(",") { it.trim() }}")

							// Required when ALL the following conditions are met :
							// - Gradle is using JDK 1.9 or higher.
							// - The version of Gradle is between 7.0 and 7.1.1 inclusively.
							// - Gradle is compiling Kotlin DSL scripts.
							// - The Kotlin daemon isn't running.
							add("-Pkotlin.daemon.jvmargs=${daemonJvmArgs.joinToString(" ") { it.trim() }}")
						}
					}
				}

				// D8/R8
				add("-Pandroid.enableR8=${tuningConfig.dex.enableR8}")

				// AAPT2
				val aapt2 = tuningConfig.aapt2
				add("-Pandroid.enableAapt2Daemon=${aapt2.enableDaemon}")
				add("-Pandroid.aapt2ThreadPoolSize=${aapt2.threadPoolSize}")
				add("-Pandroid.enableResourceOptimizations=${aapt2.enableResourceOptimizations}")
			}

		val jvmArgs = toJvmArgs(tuningConfig.gradle.jvm)

		return GradleBuildParams(
			gradleArgs = gradleArgs,
			jvmArgs = jvmArgs,
		)
	}

	private fun toJvmArgs(jvm: JvmConfig) =
		buildList {
			// Heap sizing
			add("-Xms${jvm.xmsMb}m")
			add("-Xmx${jvm.xmxMb}m")

			// Metaspace cap (class metadata)
			add("-XX:MaxMetaspaceSize=${jvm.maxMetaspaceSizeMb}m")

			// JIT code cache
			add("-XX:ReservedCodeCacheSize=${jvm.reservedCodeCacheSizeMb}m")

			// GC strategy
			when (val gc = jvm.gcType) {
				GcType.Default -> Unit
				GcType.Serial -> add("-XX:+UseSerialGC")
				is GcType.Generational -> {
					add("-XX:+UseG1GC")

					when (gc.useAdaptiveIHOP) {
						true -> add("-XX:+G1UseAdaptiveIHOP")
						false -> add("-XX:-G1UseAdaptiveIHOP")
						null -> Unit
					}

					if (gc.softRefLRUPolicyMSPerMB != null) add("-XX:SoftRefLRUPolicyMSPerMB=${gc.softRefLRUPolicyMSPerMB}")
				}
			}

			// Heap dump on OOM (useful for diagnosing memory issues)
			if (jvm.heapDumpOnOutOfMemory) {
				add("-XX:+HeapDumpOnOutOfMemoryError")
			}
		}
}
