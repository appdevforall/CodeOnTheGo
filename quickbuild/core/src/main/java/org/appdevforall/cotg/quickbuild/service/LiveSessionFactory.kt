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
 * Assembles a [LiveSession] from a successful provision.
 *
 * Pure wiring: no mutable state, no back-reference into the manager. [executorFor] and
 * [annotationImpactFor] are exposed because a proxy app rebuild rebuilds those two
 * against the regenerated setup.json (see [LiveSession]'s switchable delegates). Call
 * only on the session dispatcher; [scope] belongs to the manager and is passed through to
 * the orchestrator and watcher.
 */
internal class LiveSessionFactory(
	private val daemon: QuickBuildDaemon,
	private val deploy: DeploySender,
	/** App-private scratch trees (ADFA-4930); executor work dirs live here, off FUSE. */
	private val scratch: QuickBuildScratch,
	private val launcher: ProxyAppLauncher,
	private val metrics: QuickBuildMetricsSink,
	/**
	 * Monotonic clock shared by the orchestrator's t0 stamp and the executor's t1-t3, so
	 * the e2e timeline's stamps are comparable (see
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
	/** Wires a session around the provisioned proxy app, ready to accept edits. */
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
				classifier = ChangeClassifier(annotationImpact, layout.liveReloadScope()),
				scope = scope,
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

	/**
	 * Builds the executor for one proxy app baseline. Called again, and swapped in, on
	 * every proxy app rebuild.
	 */
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
				// Safe: the provisioner never reports Success for a null entryActivity,
				// it refuses with a friendly message first.
				entryActivity =
					checkNotNull(proxyApp.entryActivity) {
						"Quick Build session started without an entry activity"
					},
				generations = tracker,
				// App-private scratch, deliberately not under the FUSE-backed project root.
				workDir = scratch.workDirFor(layout.projectRoot),
				proxyClassesDir = proxyApp.proxyClassesDir,
				proxyAppManifest = proxyApp.transformedManifest,
				deployPolicy =
					DeployPolicy(
						components = proxyApp.components,
						// Pre-v2 setup.json means a runtime that ignores restart
						// deploys, so the policy routes restart-requiring builds to a
						// proxy app rebuild instead.
						componentInfoAvailable = proxyApp.supportsComponentInfo,
					),
				proxyAppPackage = proxyApp.proxyAppPackage,
				launcherActivity =
					proxyApp.components
						.firstOrNull { it.kind == ComponentKind.ACTIVITY && it.launcher }
						?.proxyClass,
				launcher = launcher,
				clock = nowMillis,
				metrics = metrics,
			)

	/**
	 * Builds the annotation-processor awareness the classifier uses to decide which edits
	 * could have moved generated code.
	 *
	 * A project with no `ksp`/`kapt`/`annotationProcessor` dependency gets
	 * [AnnotationImpact.Inactive]. Otherwise the analyzer's baseline is the annotation
	 * input the proxy app build just ran against, so this is rebuilt on every proxy app
	 * rebuild (see [SwitchableAnnotationImpact]).
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
