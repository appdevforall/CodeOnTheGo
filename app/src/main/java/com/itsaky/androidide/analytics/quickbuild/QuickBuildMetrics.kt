package com.itsaky.androidide.analytics.quickbuild

import android.os.Bundle
import com.itsaky.androidide.analytics.Metric

/**
 * Firebase metrics for the Quick Build live reload path (ADFA-4128), mirroring the Gradle
 * build metric family: started/completed pair + the live-reload-specific invalidation and
 * proxy-app-rebuild events. Payloads are low-cardinality - routes and reasons are enum-derived
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
	/**
	 * Gradle subproject count of the open project (all modules, Android or not,
	 * excluding the root build container); null when unknown - workspace not yet
	 * synced, or no supplier wired (bench/test contexts) - and then omitted from the
	 * bundle rather than sent as 0. `> 1` reads as multi-module without joining to a
	 * separate project-info event (ADFA-4128).
	 */
	val moduleCount: Int? = null,
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
			moduleCount?.let { putInt("module_count", it) }
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

/**
 * The end-to-end live-reload loop for one generation: the user-perceived save->live time
 * ([totalMs]) and a per-stage split that adds up to it (ADFA-4128 e2e-timing). Keyed by
 * (qbSessionId, generation) - the same generation the completed event reports - so the
 * timing joins to route/outcome without carrying either here. All stamps are device-local
 * `elapsedRealtime` deltas; everything else is a counter. No paths, file names, or source
 * content leave the device.
 *
 * The spans have to cover the whole loop, not just compilation: source scan, Java-ABI
 * snapshot, the two output-tree walks and the deploy-policy class-header pass are where the
 * dominant cost lives (per-file I/O on FUSE-backed emulated storage), while javac is only
 * 19-27% of a warm edit. [unaccountedMs] keeps the split honest - it is whatever no span
 * measured, so an un-timed step shows up as a visible number instead of quietly inflating
 * its neighbour. [queueMs] is broken out of that residual for the same reason: it is a save
 * waiting behind another build, not build work, and must not be read as build cost.
 *
 * Bundle size is deliberate. Firebase caps a custom event at [MAX_EVENT_PARAMS]
 * parameters, and [com.itsaky.androidide.analytics.AnalyticsManager.trackMetric] adds a
 * `timestamp` on top of these, so the worst-case route must stay under that cap - a test
 * enforces it, and with [queueMs] there is no headroom left: another field means dropping
 * one. The finer daemon-internal timings (the aapt2 pair, the two walks separately) live in
 * the bench `reload_timeline` event, which has no such limit; here they are summed or
 * omitted.
 */
data class QuickBuildReloadTimingMetric(
	val qbSessionId: String,
	val generation: Long,
	/** Full loop: file-watch trigger -> new code live on screen. */
	val totalMs: Long,
	/** Trigger -> compiled+dexed (relink+package for a no-compile route). */
	val compileMs: Long,
	/** Compiled -> deploy sent: relink + asset packaging (~0 on code-only). */
	val stageMs: Long,
	/** Deploy sent -> confirmed live: binder round-trip + the proxy app's reload. */
	val reloadMs: Long,
	val projectHash: Long,
	/** Host spans partitioning the build half; null when unmeasured. */
	val queueMs: Long? = null,
	val scanMs: Long? = null,
	val compileRpcMs: Long? = null,
	val policyMs: Long? = null,
	val dexRpcMs: Long? = null,
	val relinkRpcMs: Long? = null,
	/** [totalMs] minus every measured span - see the class doc. Null when nothing was measured. */
	val unaccountedMs: Long? = null,
	/** Tool timings nested inside the spans above; null when the step did not run. */
	val kotlinMs: Long? = null,
	val javacMs: Long? = null,
	val stripMs: Long? = null,
	val d8Ms: Long? = null,
	/** The two output-tree walks, summed (they are reported separately to the bench event). */
	val walkMs: Long? = null,
	val javaAbiSnapMs: Long? = null,
	/** Scale of the build, for reading a slow row. */
	val kotlinDeclaredChanged: Int? = null,
	val changedClasses: Int? = null,
	/** 1 = the daemon session's cold build; above 1 = a warm edit. */
	val compileOrdinal: Long? = null,
	/** Filesystem of the daemon scratch tree - the top predictor of every duration here. */
	val scratchFs: String? = null,
) : Metric {
	override val eventName = "quick_build_reload_timing"

	override fun asBundle(): Bundle =
		Bundle().apply {
			putString("qb_session_id", qbSessionId)
			putLong("generation", generation)
			putLong("total_ms", totalMs)
			putLong("compile_ms", compileMs)
			putLong("stage_ms", stageMs)
			putLong("reload_ms", reloadMs)
			putLong("project_hash", projectHash)
			queueMs?.let { putLong("queue_ms", it) }
			scanMs?.let { putLong("scan_ms", it) }
			compileRpcMs?.let { putLong("compile_rpc_ms", it) }
			policyMs?.let { putLong("policy_ms", it) }
			dexRpcMs?.let { putLong("dex_rpc_ms", it) }
			relinkRpcMs?.let { putLong("relink_rpc_ms", it) }
			unaccountedMs?.let { putLong("unaccounted_ms", it) }
			kotlinMs?.let { putLong("kotlin_ms", it) }
			javacMs?.let { putLong("javac_ms", it) }
			stripMs?.let { putLong("strip_ms", it) }
			d8Ms?.let { putLong("d8_ms", it) }
			walkMs?.let { putLong("walk_ms", it) }
			javaAbiSnapMs?.let { putLong("java_abi_snap_ms", it) }
			kotlinDeclaredChanged?.let { putInt("n_kotlin_declared_changed", it) }
			changedClasses?.let { putInt("n_changed_classes", it) }
			compileOrdinal?.let { putLong("compile_ordinal", it) }
			scratchFs?.let { putString("scratch_fs", it) }
		}

	companion object {
		/**
		 * Firebase's hard cap on parameters per custom event. `trackMetric` adds one
		 * (`timestamp`) after [asBundle], so the bundle itself must stay strictly below it.
		 */
		const val MAX_EVENT_PARAMS = 25
	}
}

/** The changed-set forced the session off the live reload path (route = FullGradleBuild). */
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

/** A proxy app rebuild (full setup rebuild) finished; the cost of every fallback route. */
data class QuickBuildProxyAppRebuildMetric(
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
