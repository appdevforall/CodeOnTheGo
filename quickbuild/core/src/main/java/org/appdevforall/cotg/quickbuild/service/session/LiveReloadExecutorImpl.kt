package org.appdevforall.cotg.quickbuild.service.session

import kotlinx.coroutines.CancellationException
import org.appdevforall.cotg.quickbuild.data.AssetPackager
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.RelinkInputs
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.DeployDecision
import org.appdevforall.cotg.quickbuild.domain.reload.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.service.deploy.BuildStatusJson
import org.appdevforall.cotg.quickbuild.service.deploy.DeploySender
import org.appdevforall.cotg.quickbuild.service.deploy.PayloadDeployer
import org.appdevforall.cotg.quickbuild.service.deploy.RetainedPayloadStore
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.appdevforall.cotg.quickbuild.service.telemetry.E2eTimelineRecorder
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Turns one classified changed-set into new code running in the proxy app.
 *
 * Every failure becomes a [BuildOutcome] rather than escaping, and a generation is allocated
 * only once the build steps succeed, so a compile error burns none. After a successful compile
 * [deployPolicy] picks hot swap or process restart - a recompiled service, provider, or
 * Application class cannot be swapped into a live instance - and [PayloadDeployer] owns the rest.
 */
class LiveReloadExecutorImpl(
	/** Warm compile/dex/relink server; a death mid-build surfaces as a daemon-died outcome. */
	private val daemon: QuickBuildDaemon,
	/** Binder channel to the running proxy app; also carries the build-status notifications. */
	private val deploy: DeploySender,
	/** Source, resource, and manifest roots of the user's module, re-read on every build. */
	private val layout: QuickBuildProjectLayout,
	/** The user app's entry activity FQN, echoed to the runtime in payload metadata. */
	private val entryActivity: String,
	/** Allocates generations; only a build that reaches deploy is allowed to burn one. */
	private val generations: GenerationTracker,
	/** Scratch dir for payload staging (the changed-assets zip). */
	private val workDir: File,
	/**
	 * The proxy app build's proxy classes, bundled into every payload dex. The manifest's
	 * proxy components extend user classes, so a payload without them cannot be loaded.
	 */
	private val proxyClassesDir: File? = null,
	/** The proxy app build's transformed manifest; relinks link against it when present. */
	private val proxyAppManifest: File? = null,
	/** Restart-vs-recreate decision. Null, for a session without one, always hot-swaps. */
	private val deployPolicy: DeployPolicy? = null,
	/** The installed proxy app's applicationId; restart relaunch target. */
	private val proxyAppPackage: String? = null,
	/**
	 * Launcher proxy activity FQN from the transformed manifest, the restart relaunch
	 * target. Null when the MAIN/LAUNCHER filter sits on an `<activity-alias>` that no
	 * proxied activity carries; the relaunch then uses the package's default launch
	 * intent.
	 */
	private val launcherActivity: String? = null,
	/** Relaunches the proxy app on the restart path. Null makes a restart deploy fail honestly. */
	private val launcher: ProxyAppLauncher? = null,
	/** How long a relaunched proxy app gets to boot, bind, and report its generation. */
	private val restartReconnectTimeoutMillis: Long = DEFAULT_RESTART_RECONNECT_TIMEOUT_MILLIS,
	/** Monotonic clock for the e2e timeline; must be the one the orchestrator stamps t0 with. */
	private val clock: () -> Long = System::currentTimeMillis,
	/**
	 * Analytics channel for the per-generation timeline. The app wires the Firebase-backed
	 * sink; the default no-op keeps existing callers and tests unchanged.
	 */
	private val metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop,
) : LiveReloadExecutor {
	/** Builds the changed-assets zip. */
	private val assetPackager = AssetPackager()

	/**
	 * Last-deployed retention for the reconnect re-send, keyed off [workDir] so the session
	 * manager reads the same location this executor writes (see [RetainedPayloadStore.forWorkDir]).
	 */
	private val retention = RetainedPayloadStore.forWorkDir(workDir)

	/**
	 * Whether the build now running answers a Quick Build tap, which is what lets its deploy
	 * bring the proxy app forward. Seeded from each request and raised in place by
	 * [markCurrentBuildUserInitiated], so a tap that lands mid-build still counts. Volatile
	 * because the promotion arrives on the orchestrator's lock, not the build's coroutine.
	 */
	@Volatile private var currentBuildUserInitiated = false

	private val payloadDeployer =
		PayloadDeployer(
			deploy = deploy,
			generations = generations,
			entryActivity = entryActivity,
			proxyAppPackage = proxyAppPackage,
			launcherActivity = launcherActivity,
			launcher = launcher,
			restartDisconnectTimeoutMillis = DEFAULT_RESTART_DISCONNECT_TIMEOUT_MILLIS,
			restartReconnectTimeoutMillis = restartReconnectTimeoutMillis,
			clock = clock,
			reportTimeline = ::reportTimeline,
			userInitiated = { currentBuildUserInitiated },
			retention = retention,
		)

	override fun markCurrentBuildUserInitiated() {
		currentBuildUserInitiated = true
	}

	override suspend fun execute(request: BuildRequest): BuildOutcome =
		try {
			currentBuildUserInitiated = request.userInitiated
			val outcome = executeInner(request)
			// A warm compile recompiles what the proxy app already runs and deploys
			// nothing, so flashing build-ok or build-failed on its overlay would announce
			// a build the user never triggered. The outcome still flows to the
			// orchestrator for recovery routing.
			if (request.route !is BuildRoute.WarmCompile) notifyProxyApp(outcome)
			outcome
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.error("Quick build #{} pipeline failure", request.buildId, e)
			BuildOutcome.InfrastructureFailure(e.message ?: e.javaClass.name)
		}

	/**
	 * Tells the proxy app about a build that shipped no payload, so it never runs old
	 * code with nothing on screen to say why.
	 *
	 * A compile error shows; a success clears a previously shown failure. Best-effort by
	 * contract.
	 *
	 * @param outcome the build's verdict; only compile errors and successes say anything,
	 *   because every other outcome already has a surface of its own
	 */
	private fun notifyProxyApp(outcome: BuildOutcome) {
		try {
			when (outcome) {
				is BuildOutcome.CompileError -> {
					deploy.notifyBuildStatus(BuildStatusJson.buildFailed(outcome.diagnostics))
				}

				is BuildOutcome.Success -> {
					deploy.notifyBuildStatus(BuildStatusJson.buildOk())
				}

				// Deploy and infrastructure failures surface in CoGo's own status UI,
				// and a RequiresProxyAppRebuild goes through the session's fallback
				// flow, so the proxy app has nothing to add.
				else -> {
					Unit
				}
			}
		} catch (e: Exception) {
			// Best-effort messaging must never rewrite a real outcome: a throw here
			// would turn a CompileError into an InfrastructureFailure upstream.
			log.warn("Build-status notification failed", e)
		}
	}

	/**
	 * Hands a completed timeline to the log line and the analytics sink.
	 *
	 * The log line is one structured [E2eTimeline.format] line at INFO, which is what the
	 * benchmark harness parses. The metrics call is guarded so a misbehaving sink degrades to
	 * a warning rather than failing a build the user already saw reload.
	 *
	 * @param timeline the finished timeline; reported only for a build that actually went
	 *   live, so a failed build never emits a line the harness would parse
	 */
	private fun reportTimeline(timeline: E2eTimeline) {
		log.info(timeline.format())
		try {
			metrics.onReloadTimeline(timeline)
		} catch (e: Throwable) {
			log.warn("Quick Build reload-timing metric failed", e)
		}
	}

	/**
	 * Runs one build request down its route; [execute] adds the error boundary.
	 *
	 * @param request the classified build; its route selects the pipeline and its
	 *   `forced` flag is what makes an empty change-set still deploy
	 * @return the outcome for the orchestrator; may throw, which is why [execute] wraps it.
	 */
	private suspend fun executeInner(request: BuildRequest): BuildOutcome {
		val startedAt = clock()
		val timeline = E2eTimelineRecorder(request.triggeredAtMillis) { daemon.scratchFsType }
		// The reported duration is the whole save-to-live loop, measured from t0 - the same span
		// the timeline totals, so the two never disagree. A stamp of 0 means the caller has no
		// clock (see BuildRequest.triggeredAtMillis): there is then no t0, so fall back to this
		// build's own start rather than measuring from the epoch, and report no queue at all.
		val triggeredAt = request.triggeredAtMillis
		val loopStartedAt = if (triggeredAt > 0) triggeredAt else startedAt
		if (triggeredAt > 0) timeline.recordQueue(startedAt - triggeredAt)

		if (request.route is BuildRoute.WarmCompile) {
			// Compile and dex everything once to warm kotlinc, the classpath snapshot,
			// the IC caches and d8, but deploy nothing: the proxy app already runs these
			// sources and the generation must not move. Nothing reloaded, so there is no
			// timeline to report.
			val dex = compileAndDex(ChangedFiles.Unknown, timeline)
			if (dex is Step.Fail) return dex.outcome
			return BuildOutcome.Success(generations.current, clock() - loopStartedAt)
		}

		val known = request.changes as? ChangedFiles.Known
		// Removed assets must reach the packager too, or a save that only deletes one
		// packages nothing and the build never deploys.
		val assetCandidates = known?.files.orEmpty() + known?.removed.orEmpty()
		val assets =
			assetPackager.packageAssets(
				changedFiles = assetCandidates,
				assetRoots = layout.assetRoots(),
				outFile = File(workDir, "assets-payload.zip"),
			)

		return when (request.route) {
			BuildRoute.NoOp -> {
				if (!request.forced) {
					// The orchestrator does not start empty unforced builds; answering
					// benignly keeps the executor total anyway.
					BuildOutcome.Success(generations.current, 0)
				} else {
					// Explicit tap with nothing changed: rebuild the current sources and ship
					// them at a fresh generation, which is how a relaunched proxy app on the
					// gen-0 baseline catches up. Replaying the current generation cannot work
					// (the runtime drops anything not strictly newer) and a null-dex payload at
					// a newer generation would advance the app past the classes it claims.
					//
					// This route has no changed-set to derive asset candidates from (the
					// classifier ran on nothing), so `assets` above is always empty here. Ship
					// every asset under the roots instead: a fresh generation with no assets
					// looks live but is missing everything the runtime never had, which a
					// later, unrelated build would then appear to have "fixed" by accident.
					val dex = compileAndDex(ChangedFiles.Unknown, timeline)
					if (dex is Step.Fail) return dex.outcome
					val arsc = relink(timeline)
					if (arsc is Step.Fail) return arsc.outcome
					payloadDeployer.deploy(
						(dex as Step.Ok).decision,
						dex.file,
						(arsc as Step.Ok).file,
						packageAllAssets(),
						loopStartedAt,
						timeline,
					)
				}
			}

			BuildRoute.CodeOnly -> {
				val dex = compileAndDex(request.changes, timeline)
				when (dex) {
					is Step.Fail -> {
						dex.outcome
					}

					is Step.Ok -> {
						payloadDeployer.deploy(dex.decision, dex.file, null, assets, loopStartedAt, timeline)
					}
				}
			}

			BuildRoute.ResourcesOnly -> {
				when (val arsc = relink(timeline)) {
					is Step.Fail -> {
						arsc.outcome
					}

					is Step.Ok -> {
						// No code moved, so the deploy policy has no say and a recreate
						// is always enough.
						payloadDeployer.deploy(
							DeployDecision.Recreate,
							null,
							arsc.file,
							assets,
							loopStartedAt,
							timeline,
						)
					}
				}
			}

			BuildRoute.CodeAndResources -> {
				val dex = compileAndDex(request.changes, timeline)
				if (dex is Step.Fail) return dex.outcome
				val arsc = relink(timeline)
				if (arsc is Step.Fail) return arsc.outcome
				payloadDeployer.deploy(
					(dex as Step.Ok).decision,
					dex.file,
					(arsc as Step.Ok).file,
					assets,
					loopStartedAt,
					timeline,
				)
			}

			BuildRoute.AssetsOnly -> {
				if (assets == null) {
					// The classifier said assets-only but nothing packaged, for instance
					// a deletion of a file that was already gone.
					BuildOutcome.Success(generations.current, clock() - loopStartedAt)
				} else {
					payloadDeployer.deploy(DeployDecision.Recreate, null, null, assets, loopStartedAt, timeline)
				}
			}

			is BuildRoute.FullGradleBuild -> {
				// Contract: the orchestrator never routes this here. Refuse honestly.
				BuildOutcome.InfrastructureFailure(
					"FullGradleBuild route must not reach the live reload path",
				)
			}

			BuildRoute.WarmCompile -> {
				// Handled by the early branch above; unreachable, kept for exhaustiveness.
				BuildOutcome.InfrastructureFailure("WarmCompile route fell through the warm-compile branch")
			}
		}
	}

	/**
	 * Compiles and dexes the changed sources, and decides how the result must be
	 * deployed.
	 *
	 * [ChangedFiles.Unknown] recompiles everything, re-seeding incremental state.
	 *
	 * @param changes the classified change-set; only `.kt` and `.java` entries reach the
	 *   compiler, and removed sources travel separately so their outputs get deleted
	 * @param timeline mutated in place with this step's spans and counts
	 * @return the dex plus its deploy decision, or the outcome that ends the build
	 */
	private suspend fun compileAndDex(
		changes: ChangedFiles,
		timeline: E2eTimelineRecorder,
	): Step {
		// One clock read per step boundary rather than per step, so the spans abut
		// exactly and any residual is real un-timed work.
		val scanStartedAt = clock()
		val allSources = layout.allSources()
		val scanDoneAt = clock()
		timeline.recordScan(scanDoneAt - scanStartedAt)
		val changedSources =
			when (changes) {
				ChangedFiles.Unknown -> {
					allSources
				}

				is ChangedFiles.Known -> {
					changes.files.filter { it.extension == "kt" || it.extension == "java" }
				}
			}
		// Removed sources are gone from disk and so absent from allSources; pass them
		// separately so the incremental compiler deletes their outputs and recompiles
		// dependents. Unknown re-seeds everything and needs no removed set.
		val removedSources =
			when (changes) {
				ChangedFiles.Unknown -> {
					emptyList()
				}

				is ChangedFiles.Known -> {
					changes.removed.filter { it.extension == "kt" || it.extension == "java" }
				}
			}

		val compileReply = daemon.compile(allSources, changedSources, removedSources)
		val compileDoneAt = clock()
		timeline.recordCompileRpc(compileDoneAt - scanDoneAt)
		val compiled =
			when (compileReply) {
				is DaemonReply.Ok -> {
					compileReply.value
				}

				is DaemonReply.BuildFailed -> {
					// The counts ride the outcome because this path never reaches the timeline:
					// reportTimeline runs only from PayloadDeployer's success arms, which need a
					// generation and a live timestamp a failed compile does not have.
					return Step.Fail(
						BuildOutcome.CompileError(
							compileReply.diagnostics,
							kotlinDeclaredChanged = compileReply.stats?.kotlinToCompile,
							allSources = compileReply.stats?.allSources,
							javaSources = compileReply.stats?.javaSources,
						),
					)
				}

				is DaemonReply.Failed -> {
					return Step.Fail(BuildOutcome.InfrastructureFailure(compileReply.message, compileReply.daemonDied))
				}
			}
		timeline.recordCompileSteps(compiled.kotlinMillis, compiled.javaMillis, compiled.stats)

		val decision = decideDeploy(compiled.changedClassFiles)
		val policyDoneAt = clock()
		timeline.recordPolicy(policyDoneAt - compileDoneAt)

		val dexReply = daemon.dex(listOfNotNull(compiled.classesDir, proxyClassesDir))
		val dexDoneAt = clock()
		timeline.recordDexRpc(dexDoneAt - policyDoneAt)
		return when (val reply = dexReply) {
			is DaemonReply.Ok -> {
				// t1: the deployable dex exists. Dexing dominates an on-device build, so
				// it belongs inside compileMillis rather than after it.
				timeline.markCompileDone(dexDoneAt)
				timeline.recordDexSteps(reply.value.stripMillis, reply.value.d8Millis, reply.value.stats)
				Step.Ok(reply.value.dexFile, decision)
			}

			is DaemonReply.BuildFailed -> {
				Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
			}

			is DaemonReply.Failed -> {
				Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
			}
		}
	}

	/**
	 * Asks the deploy policy for a hot swap or a restart.
	 *
	 * @param changedClassFiles the changed classes; the policy reads them only to spot a compile
	 *   that emitted nothing, since the payload carries the whole class set either way
	 * @return the route the deploy must take; always recreate when no policy is wired
	 */
	private fun decideDeploy(changedClassFiles: List<String>?): DeployDecision =
		deployPolicy?.decide(changedClassFiles) ?: DeployDecision.Recreate

	/**
	 * Rebuilds the resource APK from the project's current resources.
	 *
	 * @param timeline mutated in place with the relink's rpc and aapt2 spans
	 * @return the resource APK, or the outcome that ends the build; aapt2 errors come back
	 *   as a compile error, since they are the user's resources failing to build
	 */
	private suspend fun relink(timeline: E2eTimelineRecorder): Step {
		val startedAt = clock()
		val reply =
			daemon.relink(
				RelinkInputs(
					resDirs = layout.resDirs(),
					manifest = proxyAppManifest ?: layout.manifest(),
					stableIdsFile = layout.stableIdsFile(),
					libraryResources = layout.libraryResourceFlats(),
				),
			)
		timeline.recordRelinkRpc(clock() - startedAt)
		return when (reply) {
			is DaemonReply.Ok -> {
				timeline.recordRelinkSteps(reply.value.aapt2CompileMillis, reply.value.aapt2LinkMillis)
				Step.Ok(reply.value.resourceApk, DeployDecision.Recreate)
			}

			// aapt2 errors are the user's resources failing to build, which is a compile
			// error in the domain's sense, with aapt2's diagnostics attached.
			is DaemonReply.BuildFailed -> {
				Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
			}

			is DaemonReply.Failed -> {
				Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
			}
		}
	}

	/**
	 * Packages every file under [QuickBuildProjectLayout.assetRoots], for a forced rebuild that
	 * has no changed-set to derive asset candidates from.
	 *
	 * @return the full asset set as [AssetPackager] would ship it, or null when the module has
	 *   no assets at all.
	 */
	private fun packageAllAssets(): AssetPackager.PackagedAssets? =
		assetPackager.packageAssets(
			changedFiles = layout.assetRoots().flatMap { it.walkTopDown().filter(File::isFile).toList() },
			assetRoots = layout.assetRoots(),
			outFile = File(workDir, "assets-payload.zip"),
		)

	/** Result of one pipeline step: the artifact it produced, or the outcome that ends the build. */
	private sealed interface Step {
		/**
		 * The step produced an artifact.
		 *
		 * @property file the artifact - a dex for compile-and-dex, a resource APK for relink.
		 * @property decision how the artifact must be deployed; always recreate for a step
		 *   that moved no code.
		 */
		data class Ok(
			val file: File,
			val decision: DeployDecision,
		) : Step

		/**
		 * The step ended the build.
		 *
		 * @property outcome the verdict to return unchanged, already in the shape the
		 *   orchestrator routes on.
		 */
		data class Fail(
			val outcome: BuildOutcome,
		) : Step
	}

	private companion object {
		private val log = LoggerFactory.getLogger("QB-ReloadExecutor")

		/**
		 * How long the runtime gets to exit after acking a restart deploy. Far more than
		 * it needs, so hitting it at all means the runtime ignored the request.
		 */
		const val DEFAULT_RESTART_DISCONNECT_TIMEOUT_MILLIS = 5_000L

		/**
		 * How long the relaunched process gets to boot, bind, and connect back. Sized for
		 * a cold app start on low-end hardware, which is also why it bounds the rebind
		 * wait in [PayloadDeployer]'s launch-and-retry.
		 */
		const val DEFAULT_RESTART_RECONNECT_TIMEOUT_MILLIS = 15_000L
	}
}
