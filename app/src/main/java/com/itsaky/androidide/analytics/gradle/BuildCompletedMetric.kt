package com.itsaky.androidide.analytics.gradle

import android.os.Bundle
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.result.BuildResult

/**
 * @author Akash Yadav
 */
class BuildCompletedMetric(
	override val buildId: BuildId,
	val buildType: String,
	val isSuccess: Boolean,
	val buildResult: BuildResult,
) : BuildMetric() {
	override val eventName = "build_completed"

	override fun asBundle(): Bundle =
		super.asBundle().apply {
			putString("build_type", buildType)
			putBoolean("success", isSuccess)
			putLong("duration_ms", buildResult.durationMs)
		}
}
