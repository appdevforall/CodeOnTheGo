package com.itsaky.androidide.quickbuild

import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Every hook the ADFA-4128 benchmark harness needs from shipping code, in one place, in the
 * debug source set. `src/release/` carries a no-op twin with the same signatures, so a
 * release APK contains no benchmark code at all - same debug/release pair as
 * [com.itsaky.androidide.app.LeakCanaryConfig].
 *
 * Every hook is additionally gated on [isEnabled] (the `CodeOnTheGo.qbbench` flag file), so
 * a debug build with the flag absent behaves exactly like a release one.
 */
internal object QuickBuildBenchHooks {
	/**
	 * Whether the benchmark interface is on at all. Callers check this before doing any work
	 * to build a hook's arguments (a canonical-path resolution, say); every hook re-checks it
	 * so an unguarded call is still inert.
	 */
	val isEnabled: Boolean
		get() = FeatureFlags.isQuickBuildBenchEnabled

	/**
	 * Claims a pending autostart for [projectPath] (canonical), converting the harness's wire
	 * mode into the editor's [AutostartBuild]. One-shot: a claimed autostart is consumed.
	 */
	fun claimAutostart(projectPath: String): AutostartBuild {
		if (!isEnabled) return AutostartBuild.NONE
		return when (QuickBuildBenchAutostart.claim(projectPath)) {
			QuickBuildBenchAutostart.MODE_QUICK_BUILD -> AutostartBuild.QUICK_BUILD
			QuickBuildBenchAutostart.MODE_STANDARD -> AutostartBuild.STANDARD
			else -> AutostartBuild.NONE
		}
	}

	/**
	 * Stamps the start of an autostarted standard build and arms the latch
	 * [standardBuildEnded] reads.
	 */
	fun standardBuildStarted(
		projectPath: String,
		modulePath: String,
		variantName: String,
	) {
		if (!isEnabled) return
		standardBuildStartMs = System.currentTimeMillis()
		events()?.append("standard_build_started") {
			put("project", projectPath)
			put("module", modulePath)
			put("variant", variantName)
		}
	}

	/**
	 * Stamps the end of an autostarted standard build. [isTerminal] is false while the build
	 * is still running; [isSuccess] says whether the terminal state produced something
	 * installable.
	 *
	 * Returns true iff the caller must SUPPRESS the install this build state would normally
	 * trigger: the measurement ends at the build result, and an unattended run must not pop
	 * an install dialog. False whenever no autostarted build is in flight - which is always,
	 * in a release build - so a human's build installs as usual.
	 */
	fun standardBuildEnded(
		isTerminal: Boolean,
		isSuccess: Boolean,
	): Boolean {
		val startMs = standardBuildStartMs ?: return false
		if (!isTerminal) return false
		standardBuildStartMs = null
		events()?.append("standard_build_finished") {
			put("isSuccess", isSuccess)
			put("durationMs", System.currentTimeMillis() - startMs)
		}
		return true
	}

	/**
	 * An extra metrics sink that mirrors every callback into the JSON-lines event log, or
	 * null when the bench flag is off. Fanned in alongside the shipping sinks.
	 */
	fun metricsSink(): QuickBuildMetricsSink? {
		if (!isEnabled) return null
		return events()?.let(::BenchQuickBuildMetricsSink)
	}

	/**
	 * Mirrors session-state changes into the event log - a second, read-only collector on
	 * the session manager's existing stream, so the UI's own collector is untouched.
	 */
	fun attachStateRecorder(state: StateFlow<QuickBuildSessionState>) {
		if (!isEnabled) return
		val events = events() ?: return
		BenchStateRecorder(events)
			.attach(state, CoroutineScope(SupervisorJob() + Dispatchers.IO))
	}

	/**
	 * Whether the post-provisioning background warm compile runs. `CodeOnTheGo.qbnoseed`
	 * suppresses it so an A/B runs against the same installed build; inert unless the bench
	 * flag is on too, and absent entirely from a release build.
	 */
	fun warmCompileEnabled(): Boolean = !(isEnabled && FeatureFlags.isQuickBuildWarmCompileDisabled)

	/**
	 * Start time of an in-flight autostarted standard build, or null when none is running.
	 * Written on the project-init path, read on the build-state collector - hence volatile.
	 */
	@Volatile
	private var standardBuildStartMs: Long? = null

	@Volatile
	private var eventsFile: BenchEventsFile? = null

	/**
	 * The shared JSON-lines writer, created on first use so a debug build with the flag off
	 * never touches the filesystem. One instance per process: [BenchEventsFile] serializes
	 * its own writes, which only helps if every writer shares it.
	 */
	@Synchronized
	private fun events(): BenchEventsFile? {
		eventsFile?.let { return it }
		return runCatching {
			val paths = GlobalContext.get().get<EnvironmentQuickBuildPaths>()
			BenchEventsFile(File(paths.quickBuildHome, "bench-events.jsonl"))
		}.onFailure { log.error("Bench events file unavailable", it) }
			.getOrNull()
			?.also { eventsFile = it }
	}

	private val log = LoggerFactory.getLogger("QB-BenchHooks")
}
