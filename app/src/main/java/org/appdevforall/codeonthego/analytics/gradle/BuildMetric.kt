package org.appdevforall.codeonthego.analytics.gradle

import android.os.Bundle
import org.appdevforall.codeonthego.analytics.Metric
import org.appdevforall.codeonthego.tooling.api.messages.BuildId

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
			putString("run_type", buildId.runType.typeName)
		}
}
