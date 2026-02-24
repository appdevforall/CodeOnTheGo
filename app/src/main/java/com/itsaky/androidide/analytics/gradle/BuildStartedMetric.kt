package com.itsaky.androidide.analytics.gradle

import android.os.Bundle
import com.itsaky.androidide.services.builder.GcType
import com.itsaky.androidide.services.builder.GradleTuningConfig
import com.itsaky.androidide.services.builder.JvmConfig
import com.itsaky.androidide.services.builder.KotlinCompilerExecution
import com.itsaky.androidide.tooling.api.messages.BuildId

/**
 * Information about a build event.
 *
 * @author Akash Yadav
 */
data class BuildStartedMetric(
	override val buildId: BuildId,
	val buildType: String,
	val projectPath: String,
	val tuningConfig: GradleTuningConfig? = null,
) : BuildMetric() {
	override val eventName = "build_started"

	override fun asBundle(): Bundle =
		super.asBundle().apply {
			putLong("project_hash", projectPath.hashCode().toLong())
			putString("build_type", buildType)

			putBoolean("is_tuned_build", tuningConfig != null)
			if (tuningConfig == null) {
				return@apply
			}

			tuningConfig.apply {
				gradle.apply {
					putBoolean("gradle_daemon_enabled", daemonEnabled)

					record("gradle", jvm)
				}

				putString("kt_execution_strategy", kotlin.name)
				putBoolean("kt_incremental", kotlin.incremental)
				if (kotlin is KotlinCompilerExecution.Daemon) {
					record("kt_daemon", kotlin.jvm)
				}

				aapt2.apply {
					putBoolean("aapt2_daemon_enabled", enableDaemon)
					putInt("aapt2_thread_pool_size", threadPoolSize)
					putBoolean("aapt2_res_optimizations", enableResourceOptimizations)
				}
			}
		}

	@Suppress("NOTHING_TO_INLINE")
	private inline fun Bundle.record(
		prefix: String,
		config: JvmConfig,
	) = config.apply {
		putInt("${prefix}_xms_mb", xmsMb)
		putInt("${prefix}_xmx_mb", xmxMb)
		putInt("${prefix}_max_meta_size_mb", maxMetaspaceSizeMb)
		putInt("${prefix}_resrvd_ccache_size_mb", reservedCodeCacheSizeMb)

		putString("${prefix}_gc_type", gcType.name)
		if (gcType is GcType.Generational) {
			putString("${prefix}_adaptive_ihop", gcType.useAdaptiveIHOP?.toString())
			putString("${prefix}_softref_lru_policy_ms_per_mb", gcType.softRefLRUPolicyMSPerMB?.toString())
		}

		putBoolean("${prefix}_heap_dump_on_oom", heapDumpOnOutOfMemory)
	}
}
