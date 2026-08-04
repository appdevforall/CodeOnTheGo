package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.annotations.SwitchableAnnotationImpact

/**
 * Holds the wiring of one live Quick Build session: orchestrator, watcher, tracker, and
 * the ProxyAppInfo-derived pieces a proxy app rebuild replaces.
 *
 * Assembled by [LiveSessionFactory]; read and mutated only by [QuickBuildSessionManager]
 * on the session dispatcher. [proxyApp] and [layout] are mutable, and [executor] and
 * [annotationImpact] are switchable delegates, so a rebuild can move the session to the
 * new baseline while keeping the orchestrator and its pending-changes bookkeeping.
 */
internal class LiveSession(
	/** The installed proxy app's baseline; replaced wholesale by [adoptBaseline]. */
	var proxyApp: ProxyAppInfo,
	/** Source, resource, and watch roots derived from the same baseline as [proxyApp]. */
	var layout: QuickBuildProjectLayout,
	/** Generation allocator, persisted per project so it survives this session. */
	val tracker: GenerationTracker,
	/** Decides which watcher events are worth a build; fixed for the session's lifetime. */
	val filter: WatchFilter,
	/** Owns coalescing, routing, and in-flight bookkeeping; survives a baseline swap. */
	val orchestrator: LiveReloadOrchestrator,
	/** Started by the manager once the session goes live, and stopped by its teardown. */
	val watcher: ProjectWatcher,
	/** Seam a proxy app rebuild swaps a fresh ProxyAppInfo-derived executor into. */
	val executor: SwitchableExecutor,
	/** Seam a proxy app rebuild swaps a fresh annotation baseline into. */
	val annotationImpact: SwitchableAnnotationImpact,
) {
	/**
	 * Newest generation a deploy verifiably landed in this session, or -1 before the
	 * first one.
	 *
	 * Reconnect catch-up compares against this rather than the allocation counter, which
	 * persists across sessions and burns numbers on failed builds. A proxy app
	 * reconnecting below it is running superseded code.
	 */
	var lastDeployedGeneration = -1L

	/**
	 * Moves this session onto the baseline a proxy app rebuild just installed.
	 *
	 * Every ProxyAppInfo-derived piece moves together: leaving one behind lets the deploy
	 * policy route on provisioning-time facts, so a newly proxied service would hot-swap
	 * and leave its live instance stale. Callers must already hold both delegates, since
	 * building them can fail and a failure must leave the old baseline intact.
	 *
	 * @param proxyApp the re-read report for the app just installed
	 * @param layout the layout derived from that same report, never the previous one
	 * @param executorDelegate executor built against [proxyApp]; must already be
	 *   constructed, since building it can throw
	 * @param annotationImpactDelegate annotation baseline captured against [proxyApp]
	 */
	suspend fun adoptBaseline(
		proxyApp: ProxyAppInfo,
		layout: QuickBuildProjectLayout,
		executorDelegate: LiveReloadExecutor,
		annotationImpactDelegate: AnnotationImpact,
	) {
		this.proxyApp = proxyApp
		this.layout = layout
		executor.delegate = executorDelegate
		annotationImpact.delegate = annotationImpactDelegate
		// The freshly installed baseline boots gen 0 again; the fingerprint gate in its
		// runtime discarded any older persisted payload.
		lastDeployedGeneration = -1L
		orchestrator.onBaselineReset()
	}
}

/**
 * Lets [LiveSession] replace its executor without replacing the orchestrator.
 *
 * The orchestrator holds one executor for its lifetime, but a proxy app rebuild has to
 * rebuild the executor from the re-read setup.json (new deploy-policy components,
 * launcher and entry targets). Swapping the delegate keeps the orchestrator's
 * pending-changes bookkeeping.
 *
 * @property delegate the executor every call forwards to; volatile because the swap runs
 *   on the session dispatcher while a build may read it from another thread
 */
internal class SwitchableExecutor(
	@Volatile var delegate: LiveReloadExecutor,
) : LiveReloadExecutor {
	override suspend fun execute(request: BuildRequest): BuildOutcome = delegate.execute(request)
}
