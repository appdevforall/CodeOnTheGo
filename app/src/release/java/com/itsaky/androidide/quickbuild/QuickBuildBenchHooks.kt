package com.itsaky.androidide.quickbuild

import kotlinx.coroutines.flow.StateFlow
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink

/**
 * No-op twin of the debug build's benchmark hooks (ADFA-4128): a release APK ships no
 * benchmark code, so there is nothing to arm, nothing to record, and no extra metrics sink.
 * Same debug/release pair as [com.itsaky.androidide.app.LeakCanaryConfig].
 *
 * [isEnabled] is a constant `false`, so every call site's bench branch is dead code.
 */
internal object QuickBuildBenchHooks {
	val isEnabled: Boolean
		get() = false

	fun claimAutostart(projectPath: String): AutostartBuild = AutostartBuild.NONE

	fun standardBuildStarted(
		projectPath: String,
		modulePath: String,
		variantName: String,
	) = Unit

	/** Never suppresses an install: without a harness, every build is a human's. */
	fun standardBuildEnded(
		isTerminal: Boolean,
		isSuccess: Boolean,
	): Boolean = false

	fun metricsSink(): QuickBuildMetricsSink? = null

	fun attachStateRecorder(state: StateFlow<QuickBuildSessionState>) = Unit

	/** The warm compile is a shipping behaviour; only the bench A/B could turn it off. */
	fun warmCompileEnabled(): Boolean = true
}
