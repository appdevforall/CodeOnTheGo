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
			// Why it was not successful, which the metric used to drop. A build the user stopped
			// and a build that broke both arrive as success=false, so without this the two are
			// indistinguishable and every build-success rate counts deliberate cancels as
			// failures. isSuccess keeps its meaning -- a cancelled build did not succeed -- and a
			// consumer that wants the rate excluding cancels can now compute it.
			buildResult.failure?.let { putString("failure_reason", it.name) }
		}
}
