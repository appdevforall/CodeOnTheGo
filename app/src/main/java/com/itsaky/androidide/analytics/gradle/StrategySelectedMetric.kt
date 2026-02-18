package com.itsaky.androidide.analytics.gradle

import android.os.Bundle
import com.itsaky.androidide.services.builder.GradleBuildTuner
import com.itsaky.androidide.services.builder.GradleTuningStrategy
import com.itsaky.androidide.tooling.api.messages.BuildId

/**
 * @author Akash Yadav
 */
class StrategySelectedMetric(
	override val buildId: BuildId,
	val totalMemBucketed: String,
	val totalCores: Int,
	val isLowMemDevice: Boolean,
	val isThermalThrottled: Boolean,
	val lowMemThresholdMb: Int,
	val highPerfMinMemMb: Int,
	val highPerfMinCores: Int,
	val hasPreviousConfig: Boolean,
	val previousStrategy: String?,
	val newStrategy: GradleTuningStrategy,
	val reason: GradleBuildTuner.SelectionReason,
) : BuildMetric() {

	override val eventName = "gradle_strategy_selected"

	override fun asBundle(): Bundle =
		super.asBundle().apply {
			putString("total_mem_bucketed", totalMemBucketed)
			putInt("total_cores", totalCores)
			putBoolean("is_low_ram_device", isLowMemDevice)
			putBoolean("is_thermal_throttled", isThermalThrottled)
			putInt("low_mem_threshold_mb", lowMemThresholdMb)
			putInt("high_perf_min_mem_mb", highPerfMinMemMb)
			putInt("high_perf_min_cores", highPerfMinCores)
			putBoolean("has_previous_config", hasPreviousConfig)
			putString("previous_strategy", previousStrategy ?: "none")
			putString("new_strategy", newStrategy.name)
			putString("selection_reason", reason.label)
		}
}