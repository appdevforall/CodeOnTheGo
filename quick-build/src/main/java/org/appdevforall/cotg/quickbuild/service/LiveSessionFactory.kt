package org.appdevforall.cotg.quickbuild.service

import kotlinx.coroutines.CoroutineScope
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.OrchestratorEvent
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationBaseline
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpactAnalyzer
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationProcessorProfile
import org.appdevforall.cotg.quickbuild.domain.annotations.SwitchableAnnotationImpact
import org.slf4j.LoggerFactory

/**
 * Assembles a [LiveSession] from a successful provision: pure wiring with zero mutable
 * state and zero back-reference into the manager - a [ProxyAppInfo] + layout + tracker
 * goes in, a wired session comes out. [executorFor] and [annotationImpactFor] are
 * public seams because a proxy app rebuild rebuilds the executor and the annotation
 * baseline against the regenerated setup.json (see [LiveSession]'s switchable delegates).
 *
 * Call only on the session dispatcher; this class holds no scope of its own - [scope]
 * is the manager's, handed through to the orchestrator and watcher it assembles.
 */
internal class LiveSessionFactory(
	private val daemon: QuickBuildDaemon,
	private val deploy: DeploySender,
	/** App-private scratch trees (ADFA-4930); executor work dirs live here, off FUSE. */
	private val scratch: QuickBuildScratch,
	private val launcher: ProxyAppLauncher,
	private val metrics: QuickBuildMetricsSink,
	/**
	 * Monotonic clock shared by the orchestrator's t0 stamp and the executor's t1-t3,
	 * so the e2e timeline's four stamps are comparable (see
	 * [org.appdevforall.cotg.quickbuild.domain.E2eTimeline]).
	 */
	private val nowMillis: () -> Long,
	/** Test seam passed through from the manager; null builds the real executor. */
	private val executorFactory: QuickBuildSessionManager.ExecutorFactory?,
	/** Test seam passed through from the manager. */
	private val watcherFactory: QuickBuildSessionManager.WatcherFactory,
	private val scope: CoroutineScope,
	private val onOrchestratorEvent: (OrchestratorEvent) -> Unit,
) {
	fun create(
		outcome: ProvisionOutcome.Success,
		tracker: GenerationTracker,
	): LiveSession {
		val layout = outcome.layout
		val proxyApp = outcome.proxyApp
		val executor = SwitchableExecutor(executorFor(proxyApp, layout, tracker))
		val annotationImpact = SwitchableAnnotationImpact(annotationImpactFor(proxyApp, layout))
		val orchestrator =
			LiveReloadOrchestrator(
				executor = executor,
				classifier = ChangeClassifier(annotationImpact, layout.fastPathScope()),
				scope = scope,
				// Same monotonic timebase the executor stamps t1-t3 with, so the e2e
				// timeline's t0 (trigger) is comparable to the rest (see E2eTimeline).
				now = nowMillis,
				onEvent = onOrchestratorEvent,
			)
		val filter = WatchFilter(layout.watchedRoots(), layout.watchedFiles())
		return LiveSession(
			proxyApp = outcome.proxyApp,
			layout = layout,
			tracker = tracker,
			filter = filter,
			orchestrator = orchestrator,
			watcher = watcherFactory.create(layout.watchedRoots(), layout.watchedFiles(), filter, scope),
			executor = executor,
			annotationImpact = annotationImpact,
		)
	}

	/** ProxyAppInfo-derived executor; rebuilt (and swapped in) on every proxy app rebuild. */
	fun executorFor(
		proxyApp: ProxyAppInfo,
		layout: QuickBuildProjectLayout,
		tracker: GenerationTracker,
	): LiveReloadExecutor =
		executorFactory?.create(proxyApp, layout, tracker)
			?: LiveReloadExecutorImpl(
				daemon = daemon,
				deploy = deploy,
				layout = layout,
				// A session only reaches here off ProvisionOutcome.Success, which the
				// provisioner never produces for a null entryActivity (ADFA-4128 Bug 10) -
				// it refuses with a friendly message first. See ProxyAppInfo.entryActivity.
				entryActivity =
					checkNotNull(proxyApp.entryActivity) {
						"Quick Build session started without an entry activity"
					},
				generations = tracker,
				// App-private scratch (ADFA-4930), NOT under the FUSE-backed project root.
				workDir = scratch.workDirFor(layout.projectRoot),
				proxyClassesDir = proxyApp.proxyClassesDir,
				proxyAppManifest = proxyApp.transformedManifest,
				deployPolicy =
					DeployPolicy(
						components = proxyApp.components,
						// Pre-v2 setup.json (no schema/components) = a baseline whose
						// runtime ignores restart deploys; the policy then routes
						// restart-requiring builds to a proxy app rebuild (skew guard).
						componentInfoAvailable = proxyApp.supportsComponentInfo,
					),
				proxyAppPackage = proxyApp.proxyAppPackage,
				launcherActivity =
					proxyApp.components
						.firstOrNull { it.kind == ComponentKind.ACTIVITY && it.launcher }
						?.proxyClass,
				launcher = launcher,
				// Monotonic device clock for durationMillis + the e2e timeline stamps; the
				// orchestrator's `now` above shares it so all four stamps are comparable.
				clock = nowMillis,
				// The e2e reload timing is an analytics deliverable (ADFA-4128): the executor
				// reports each completed timeline to the same sink the lifecycle events use.
				metrics = metrics,
			)

	/**
	 * Annotation-processor awareness for this session's baseline. A project with no
	 * `ksp`/`kapt`/`annotationProcessor` dependency gets [AnnotationImpact.Inactive] and
	 * behaves exactly as before; otherwise the classifier compares each edit against the
	 * annotation input the proxy app build actually ran against, and only edits that could
	 * have moved generated code pay a proxy app rebuild.
	 *
	 * Rebuilt on every proxy app rebuild too (see [SwitchableAnnotationImpact]): the Gradle build
	 * that just ran IS the new reference point.
	 */
	fun annotationImpactFor(
		proxyApp: ProxyAppInfo,
		layout: QuickBuildProjectLayout,
	): AnnotationImpact {
		val profile = AnnotationProcessorProfile.of(proxyApp.annotationProcessors)
		if (!profile.hasProcessors) return AnnotationImpact.Inactive
		log.info(
			"Quick build: annotation-aware classification on for processors {}",
			profile.processorCoordinates,
		)
		return AnnotationImpactAnalyzer(profile, AnnotationBaseline.capture(layout.allSources(), profile))
	}

	private companion object {
		private val log = LoggerFactory.getLogger(LiveSessionFactory::class.java)
	}
}
