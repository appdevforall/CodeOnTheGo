package com.itsaky.androidide.analytics.gradle

import android.os.Bundle
import com.itsaky.androidide.analytics.Metric
import com.itsaky.androidide.tooling.api.messages.BuildId

/**
 * Metric about a build event.
 *
 * @author Akash Yadav
 */
abstract class BuildMetric : Metric {
	/**
	 * Unique ID of the build session.
	 */
	abstract val buildId: BuildId

	override fun asBundle(): Bundle =
		Bundle().apply {
			putString("build_session_id", buildId.buildSessionId)
			putLong("build_id", buildId.buildId)
		}
}
