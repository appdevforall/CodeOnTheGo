package com.itsaky.androidide.analytics.quickbuild

import android.os.Bundle
import com.itsaky.androidide.analytics.Metric

/**
 * Firebase metrics for the Quick Build fast path (ADFA-4128), mirroring the Gradle
 * build metric family: started/completed pair + the fast-path-specific invalidation and
 * rebaseline events. Payloads are low-cardinality - routes and reasons are enum-derived
 * strings, projects are hashed like [com.itsaky.androidide.analytics.gradle.BuildStartedMetric],
 * no paths or file names ever leave the device.
 */
data class QuickBuildStartedMetric(
	val qbSessionId: String,
	val buildId: Long,
	val route: String,
	val changedFiles: Int?,
	val changedKb: Long?,
	/** File-type mix of the changed-set - which change kinds users actually make. */
	val changedKotlin: Int?,
	val changedJava: Int?,
	val changedXml: Int?,
	val changedAssets: Int?,
	val changedOther: Int?,
	val projectHash: Long,
) : Metric {
	override val eventName = "quick_build_started"

	override fun asBundle(): Bundle =
		Bundle().apply {
			putString("qb_session_id", qbSessionId)
			putLong("qb_build_id", buildId)
			putString("route", route)
			// Known vs Unknown changed-set (Unknown = crash recovery / missed events).
			putBoolean("changes_known", changedFiles != null)
			changedFiles?.let { putInt("changed_files", it) }
			changedKb?.let { putLong("changed_kb", it) }
			changedKotlin?.let { putInt("changed_kt", it) }
			changedJava?.let { putInt("changed_java", it) }
			changedXml?.let { putInt("changed_xml", it) }
			changedAssets?.let { putInt("changed_assets", it) }
			changedOther?.let { putInt("changed_other", it) }
			putLong("project_hash", projectHash)
		}
}

data class QuickBuildCompletedMetric(
	val qbSessionId: String,
	val buildId: Long,
	/** Same value as the started event's route: duration-by-change-type in one event. */
	val route: String?,
	val outcome: String,
	val isSuccess: Boolean,
	val durationMs: Long,
	val generation: Long?,
	val diagnosticsCount: Int?,
	val projectHash: Long,
) : Metric {
	override val eventName = "quick_build_completed"

	override fun asBundle(): Bundle =
		Bundle().apply {
			putString("qb_session_id", qbSessionId)
			putLong("qb_build_id", buildId)
			route?.let { putString("route", it) }
			putString("outcome", outcome)
			putBoolean("success", isSuccess)
			putLong("duration_ms", durationMs)
			generation?.let { putLong("generation", it) }
			diagnosticsCount?.let { putInt("diagnostics", it) }
			putLong("project_hash", projectHash)
		}
}

/** The changed-set forced the session off the fast path (route = FullGradleBuild). */
data class QuickBuildInvalidatedMetric(
	val qbSessionId: String,
	val reason: String,
	val projectHash: Long,
) : Metric {
	override val eventName = "quick_build_invalidated"

	override fun asBundle(): Bundle =
		Bundle().apply {
			putString("qb_session_id", qbSessionId)
			putString("reason", reason)
			putLong("project_hash", projectHash)
		}
}

/** A rebaseline (full setup rebuild) finished; the cost of every fallback route. */
data class QuickBuildRebaselineMetric(
	val qbSessionId: String,
	val isSuccess: Boolean,
	val durationMs: Long,
	val projectHash: Long,
) : Metric {
	override val eventName = "quick_build_rebaseline"

	override fun asBundle(): Bundle =
		Bundle().apply {
			putString("qb_session_id", qbSessionId)
			putBoolean("success", isSuccess)
			putLong("duration_ms", durationMs)
			putLong("project_hash", projectHash)
		}
}
