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
import org.appdevforall.cotg.quickbuild.domain.annotations.SwitchableAnnotationImpact

/**
 * One live Quick Build session's wiring: the orchestrator, watcher, tracker, and the
 * ProxyAppInfo-derived pieces a proxy app rebuild swaps out. Assembled by
 * [LiveSessionFactory]; owned (and mutated) only by
 * [QuickBuildSessionManager] on the session dispatcher.
 *
 * [proxyApp]/[layout] are mutable and [executor]/[annotationImpact] are switchable
 * delegates for the same reason: a proxy app rebuild regenerates setup.json and must
 * move the session to the new baseline without rebuilding the orchestrator (whose
 * pending-changes bookkeeping has to survive the rebuild).
 */
internal class LiveSession(
	/** Mutable: a proxy app rebuild regenerates setup.json and must move this snapshot. */
	var proxyApp: ProxyAppInfo,
	var layout: QuickBuildProjectLayout,
	val tracker: GenerationTracker,
	val filter: WatchFilter,
	val orchestrator: LiveReloadOrchestrator,
	val watcher: ProjectWatcher,
	/** Seam the proxy app rebuild swaps a fresh ProxyAppInfo-derived executor into. */
	val executor: SwitchableExecutor,
	/** Seam the proxy app rebuild swaps a fresh annotation baseline into. */
	val annotationImpact: SwitchableAnnotationImpact,
) {
	/**
	 * Newest generation a deploy verifiably landed in this session, or -1 before the
	 * first one. The reconnect catch-up compares against THIS (not the allocation
	 * counter, which persists across sessions and burns numbers on failed builds):
	 * a proxy app reconnecting below it is running code this session already
	 * superseded.
	 */
	var lastDeployedGeneration = -1L
}

/**
 * Executor indirection for [LiveSession]: the orchestrator holds one executor for
 * its lifetime, but a proxy app rebuild must rebuild the executor from the re-read
 * setup.json (new deploy-policy components, launcher/entry targets). Swapping the
 * delegate keeps the orchestrator - and its pending-changes bookkeeping - intact.
 */
internal class SwitchableExecutor(
	@Volatile var delegate: LiveReloadExecutor,
) : LiveReloadExecutor {
	override suspend fun execute(request: BuildRequest): BuildOutcome = delegate.execute(request)
}
