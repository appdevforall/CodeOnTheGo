package org.appdevforall.cotg.quickbuild.service.session

import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.annotations.SwitchableAnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.watch.WatchFilter
import org.appdevforall.cotg.quickbuild.service.deploy.RetainedPayloadStore
import java.io.File

/**
 * Wiring of one live Quick Build session, including what a proxy app rebuild replaces.
 *
 * Assembled by [LiveSessionFactory]; read and mutated only by [QuickBuildSessionManager] on the
 * session dispatcher. [proxyApp], [layout] and [watch] are mutable, and [executor] and
 * [annotationImpact] are switchable delegates, so a rebuild can move the session to the new
 * baseline while keeping the orchestrator's pending-changes bookkeeping.
 */
internal class LiveSession(
	/** The installed proxy app's baseline; replaced wholesale by [adoptBaseline]. */
	var proxyApp: ProxyAppInfo,
	/** Source, resource, and watch roots derived from the same baseline as [proxyApp]. */
	var layout: QuickBuildProjectLayout,
	/** Generation allocator, persisted per project so it survives this session. */
	val tracker: GenerationTracker,
	/**
	 * The watch set derived from the same baseline as [layout]; replaced by [adoptBaseline]
	 * when a rebuild changes it, since the roots and files it observes come from the module
	 * walk the rebuilt layout redoes.
	 */
	var watch: SessionWatch,
	/** Owns coalescing, routing, and in-flight bookkeeping; survives a baseline swap. */
	val orchestrator: LiveReloadOrchestrator,
	/** Seam a proxy app rebuild swaps a fresh ProxyAppInfo-derived executor into. */
	val executor: SwitchableExecutor,
	/** Seam a proxy app rebuild swaps a fresh annotation baseline into. */
	val annotationImpact: SwitchableAnnotationImpact,
	/**
	 * The executor's last-deployed retention, read by the manager to answer a below-deployed
	 * reconnect by re-sending instead of rebuilding (concurrency.md rules 3-4). Same work-dir
	 * location the executor writes, so it survives an executor swap.
	 */
	val retainedPayloads: RetainedPayloadStore,
	/**
	 * Build variant this session was provisioned for, or null when the provisioner does not
	 * track one. Fixed for the session's lifetime: a rebuild re-runs the same variant's
	 * assemble task, and a variant switch tears the session down rather than adopting a
	 * baseline from a different application id.
	 */
	val provisionedVariant: String? = null,
) {
	/**
	 * Newest generation verifiably running in the proxy app: the baseline generation the
	 * manager adopts from the provision's stamp, advanced by every deploy that lands; -1
	 * only until that adoption.
	 *
	 * Reconnect catch-up compares against this rather than the allocation counter, which
	 * persists across sessions and burns numbers on failed builds. A proxy app
	 * reconnecting below it is running superseded code.
	 */
	var lastDeployedGeneration = -1L

	/** The watcher currently observing the project; started by the manager, stopped by its teardown. */
	val watcher: ProjectWatcher
		get() = watch.watcher

	/**
	 * Moves this session onto the baseline a proxy app rebuild just installed.
	 *
	 * Every ProxyAppInfo-derived piece moves together: leaving one behind lets the deploy
	 * policy route on provisioning-time facts, so a newly proxied service would hot-swap
	 * and leave its live instance stale. Callers must already hold both delegates and the
	 * watch set, since building them can fail and a failure must leave the old baseline
	 * intact.
	 *
	 * @param proxyApp the re-read report for the app just installed
	 * @param layout the layout derived from that same report, never the previous one
	 * @param executorDelegate executor built against [proxyApp]; must already be
	 *   constructed, since building it can throw
	 * @param annotationImpactDelegate annotation baseline captured against [proxyApp]
	 * @param watch the watch set derived from [layout]; the current one when the rebuild
	 *   left the module set alone, in which case the running watcher stays
	 * @param baselineGeneration the generation stamped into the reinstalled APK (0 for an
	 *   unstamped build); the fresh baseline boots at it, so a reconnect at the stamp reads
	 *   in-sync instead of forcing a catch-up build
	 * @param onBatch what a replacement watcher reports its batches to; unused when [watch]
	 *   is the current one
	 */
	suspend fun adoptBaseline(
		proxyApp: ProxyAppInfo,
		layout: QuickBuildProjectLayout,
		executorDelegate: LiveReloadExecutor,
		annotationImpactDelegate: AnnotationImpact,
		watch: SessionWatch,
		baselineGeneration: Long,
		onBatch: (ChangedFiles.Known) -> Unit,
	) {
		if (watch !== this.watch) {
			// First, because starting a watcher can throw and the caller's catch then
			// expects the old baseline untouched. The new one starts before the old one
			// stops: the poll primes its fingerprints on start, so an edit landing in a
			// stop-then-start gap would be taken as baseline and never built. The overlap
			// costs at most one duplicate batch, which is one redundant build.
			val previous = this.watch.watcher
			watch.watcher.start(onBatch)
			this.watch = watch
			previous.stop()
		}
		this.proxyApp = proxyApp
		this.layout = layout
		executor.delegate = executorDelegate
		annotationImpact.delegate = annotationImpactDelegate
		// The freshly installed baseline boots at its stamp; anything deployed to the old
		// epoch is gone (its runtime's generation gate discarded older persisted payloads).
		lastDeployedGeneration = baselineGeneration
		// Retention is cumulative over the OLD baseline only; replaying it onto the fresh
		// one would resurrect code the rebuild superseded.
		retainedPayloads.clear()
		orchestrator.onBaselineReset()
	}
}

/**
 * One session's watch set: the roots and files a baseline's layout derives, the filter over
 * them, and the watcher observing them.
 *
 * Built by [LiveSessionFactory.watchFor]. The three travel together because
 * [org.appdevforall.cotg.quickbuild.data.AndroidProjectWatcher] fixes its inotify set and poll
 * list from the lists it was constructed with, and the filter rejects anything outside its
 * own: a rebaseline that adds a module has to replace all three or edits under the new module
 * produce no batch, no build and no message for the rest of the session.
 *
 * @property roots directories watched recursively
 * @property files individual files watched outside [roots]
 * @property filter decides which raw events are worth a build
 * @property watcher observes [roots] and [files] through [filter]; created unstarted
 */
internal class SessionWatch(
	val roots: List<File>,
	val files: List<File>,
	val filter: WatchFilter,
	val watcher: ProjectWatcher,
) {
	/**
	 * @return true when this watch already observes exactly [roots] and [files], so a
	 *   rebaseline that derived them can keep the running watcher
	 */
	fun observes(
		roots: List<File>,
		files: List<File>,
	): Boolean = this.roots.toSet() == roots.toSet() && this.files.toSet() == files.toSet()
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

	// Has a no-op default in the interface, so forwarding is not optional: without this a tap
	// landing on an in-flight save-build is absorbed here and the deploy never goes foreground.
	override fun markCurrentBuildUserInitiated() = delegate.markCurrentBuildUserInitiated()
}
