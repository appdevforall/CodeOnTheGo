package org.appdevforall.cotg.quickbuild.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.data.AssetPackager
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.RelinkInputs
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ClassHeader
import org.appdevforall.cotg.quickbuild.domain.DeployDecision
import org.appdevforall.cotg.quickbuild.domain.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The warm-daemon pipeline behind the domain [LiveReloadExecutor] contract: routes a
 * classified changed-set through compile/dex/relink on the daemon, then deploys the
 * artifacts to the proxy app.
 *
 * Every failure becomes a [BuildOutcome] - nothing escapes to crash the orchestrator.
 * A generation is allocated only after the build steps succeed, so a compile error
 * burns nothing and the proxy app verifiably stays on its old generation.
 *
 * After a successful compile the [deployPolicy] decides hot swap vs process restart
 * (a recompiled service/provider/Application class cannot be swapped into a live
 * instance). The restart path deploys with `"restart": "true"` metadata - the runtime
 * persists the payload, acks, and exits - then waits for the binder death, relaunches
 * the launcher proxy via [launcher], and verifies the fresh process reconnected AT the
 * deployed generation before claiming success (see [deployRestart]).
 */
class LiveReloadExecutorImpl(
	private val daemon: QuickBuildDaemon,
	private val deploy: DeploySender,
	private val layout: QuickBuildProjectLayout,
	private val entryActivity: String,
	private val generations: GenerationTracker,
	/** Scratch dir for payload staging (the changed-assets zip). */
	private val workDir: File,
	/**
	 * The proxy app build's proxy classes, bundled into every payload dex — the manifest's proxy
	 * components extend user classes, so a payload without them cannot be loaded.
	 */
	private val proxyClassesDir: File? = null,
	/** The proxy app build's transformed manifest; relinks link against it when present. */
	private val proxyAppManifest: File? = null,
	/** Restart-vs-recreate decision; null (a session without one) always hot-swaps. */
	private val deployPolicy: DeployPolicy? = null,
	/** The installed proxy app's applicationId; restart relaunch target. */
	private val proxyAppPackage: String? = null,
	/**
	 * Launcher proxy activity FQN from the transformed manifest; the restart relaunch
	 * target. Null when the MAIN/LAUNCHER filter lives on an `<activity-alias>` (no
	 * proxied activity carries it) - the relaunch then falls back to the package's
	 * default launch intent.
	 */
	private val launcherActivity: String? = null,
	private val launcher: ProxyAppLauncher? = null,
	private val restartDisconnectTimeoutMillis: Long = DEFAULT_RESTART_DISCONNECT_TIMEOUT_MILLIS,
	private val restartReconnectTimeoutMillis: Long = DEFAULT_RESTART_RECONNECT_TIMEOUT_MILLIS,
	private val assetPackager: AssetPackager = AssetPackager(),
	private val clock: () -> Long = System::currentTimeMillis,
	/**
	 * Sink for the per-generation e2e timing line (ADFA-4128 e2e-timing spec). Default logs
	 * one structured [E2eTimeline.format] line at INFO - cheap, always-on, no network. The
	 * spec explicitly leaves wiring an analytics backend as a team decision (offline-first
	 * users); this is a log line, not [org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink].
	 * Tests inject a capturing lambda.
	 */
	private val emitTimeline: (E2eTimeline) -> Unit = { log.info(it.format()) },
	/**
	 * Analytics channel for the same timeline (ADFA-4128 e2e-timing spec: the timing is an
	 * analytics deliverable, not just a log line). The app wires the Firebase-backed sink;
	 * the default no-op keeps existing callers + tests unchanged. Guarded on every call
	 * ([reportTimeline]) per the sink's contract, so telemetry can never fail a build.
	 */
	private val metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop,
) : LiveReloadExecutor {
	override suspend fun execute(request: BuildRequest): BuildOutcome =
		try {
			val outcome = executeInner(request)
			// A warm compile compiles the sources the proxy app ALREADY runs and deploys
			// nothing: flashing build-ok/build-failed on its overlay would announce
			// a build the user never triggered (2026-07-26 review). Stay silent;
			// the outcome still flows to the orchestrator for recovery routing.
			if (request.route !is BuildRoute.WarmCompile) notifyProxyApp(outcome)
			outcome
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.error("Quick build #{} pipeline failure", request.buildId, e)
			BuildOutcome.InfrastructureFailure(e.message ?: e.javaClass.name)
		}

	/**
	 * The proxy-app half of the never-stale invariant (plan A1): a compile error never
	 * produces a payload, so without this message the app would keep running old code
	 * with no on-screen signal. Success clears a previously shown failure (also on the
	 * no-payload success paths, e.g. an unforced no-op). Best-effort by contract.
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

				// Deploy/infrastructure failures surface in CoGo's own status UI; the proxy
				// app cannot say anything more truthful than what it already shows. A
				// RequiresProxyAppRebuild surfaces through the session's fallback flow.
				else -> {
					Unit
				}
			}
		} catch (e: Exception) {
			// Best-effort messaging must never rewrite a real outcome (a throw here
			// would turn e.g. a CompileError into an InfrastructureFailure upstream).
			log.warn("Build-status notification failed", e)
		}
	}

	/**
	 * Hands a completed timeline to BOTH channels: the always-on log line (the harness's
	 * local parse source) and the analytics sink (the ADFA-4128 telemetry deliverable). The
	 * metrics call is guarded so a misbehaving sink degrades to a warning and never fails a
	 * build the user already saw reload.
	 */
	private fun reportTimeline(timeline: E2eTimeline) {
		emitTimeline(timeline)
		try {
			metrics.onReloadTimeline(timeline)
		} catch (e: Throwable) {
			log.warn("Quick Build reload-timing metric failed", e)
		}
	}

	private suspend fun executeInner(request: BuildRequest): BuildOutcome {
		val startedAt = clock()
		val timeline = Timeline(request.triggeredAtMillis) { daemon.scratchFsType }

		if (request.route is BuildRoute.WarmCompile) {
			// Background warm compile: compile + dex everything once (kotlinc JIT, classpath
			// snapshot, IC caches, d8 warm-up) but deploy NOTHING - the proxy app already
			// runs exactly these sources, and the generation must not move. No timeline
			// is reported: nothing reloaded, so there is no save->live to measure.
			val dex = compileAndDex(ChangedFiles.Unknown, timeline)
			if (dex is Step.Fail) return dex.outcome
			return BuildOutcome.Success(generations.current, clock() - startedAt)
		}

		val known = request.changes as? ChangedFiles.Known
		// A removed asset must reach the packager too: it names the entry in the payload's
		// changedAssets so the runtime drops it (absence is the removal signal, AssetPackager).
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
					// Explicit tap with nothing changed: rebuild the CURRENT sources and
					// ship them at a fresh generation (e.g. catch up a killed-and-relaunched
					// proxy app running the gen-0 baseline). A metadata-only replay of the
					// current generation cannot work: the runtime only accepts strictly
					// NEWER generations (an equal one is dropped without a report), and a
					// null-dex payload at a newer generation would advance the app's
					// generation without shipping the classes it claims - a stale-code lie.
					val dex = compileAndDex(ChangedFiles.Unknown, timeline)
					if (dex is Step.Fail) return dex.outcome
					val arsc = relink(timeline)
					if (arsc is Step.Fail) return arsc.outcome
					deployDecided(
						(dex as Step.Ok).decision,
						dex.file,
						(arsc as Step.Ok).file,
						assets,
						"forced",
						startedAt,
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
						deployDecided(dex.decision, dex.file, null, assets, "code", startedAt, timeline)
					}
				}
			}

			BuildRoute.ResourcesOnly -> {
				// Resource-only deploys never restart: no code moved.
				when (val arsc = relink(timeline)) {
					is Step.Fail -> {
						arsc.outcome
					}

					is Step.Ok -> {
						deployPayload(
							generations.next(),
							null,
							arsc.file,
							assets,
							"resources",
							startedAt,
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
				deployDecided(
					(dex as Step.Ok).decision,
					dex.file,
					(arsc as Step.Ok).file,
					assets,
					"mixed",
					startedAt,
					timeline,
				)
			}

			BuildRoute.AssetsOnly -> {
				if (assets == null) {
					// Classifier said assets-only but nothing packaged (e.g. the only
					// change was a deletion of a file already gone). Nothing to ship.
					BuildOutcome.Success(generations.current, clock() - startedAt)
				} else {
					deployPayload(generations.next(), null, null, assets, "assets", startedAt, timeline)
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
	 * Compile then dex; [ChangedFiles.Unknown] recompiles everything (IC re-seed).
	 * On success also feeds the compile's changed class headers into the policy's
	 * supertype index (catches re-parenting) and computes the deploy decision.
	 */
	private suspend fun compileAndDex(
		changes: ChangedFiles,
		timeline: Timeline,
	): Step {
		// One clock read per step BOUNDARY, not per step: the spans then abut exactly, so
		// they partition [scanStartedAt, dexDoneAt] with no gap of their own making. Any
		// residual left over is real un-timed work, which is the whole point.
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
		// Removed sources are gone from disk (so out of allSources); pass them separately so
		// the incremental compiler deletes their outputs and recompiles dependents. Unknown
		// reseeds the whole world, so it carries no removed set.
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
					return Step.Fail(BuildOutcome.CompileError(compileReply.diagnostics))
				}

				is DaemonReply.Failed -> {
					return Step.Fail(BuildOutcome.InfrastructureFailure(compileReply.message, compileReply.daemonDied))
				}
			}
		timeline.recordCompileSteps(compiled.kotlinMillis, compiled.javaMillis, compiled.stats)

		val decision = decideDeploy(compiled.classesDir, compiled.changedClassFiles)
		val policyDoneAt = clock()
		timeline.recordPolicy(policyDoneAt - compileDoneAt)

		val dexReply = daemon.dex(listOfNotNull(compiled.classesDir, proxyClassesDir))
		val dexDoneAt = clock()
		timeline.recordDexRpc(dexDoneAt - policyDoneAt)
		return when (val reply = dexReply) {
			is DaemonReply.Ok -> {
				// t1: classes are compiled + dexed (the deployable dex exists). On-device
				// dexing dominates the build, so it belongs inside compileMillis, not after.
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

	private fun decideDeploy(
		classesDir: File,
		changedClassFiles: List<String>?,
	): DeployDecision {
		val policy = deployPolicy ?: return DeployDecision.Recreate
		changedClassFiles?.forEach { relative ->
			val header =
				runCatching { ClassHeader.parse(File(classesDir, relative).readBytes()) }.getOrNull()
					?: return@forEach // unreadable class: skip; the closure seed still covers it
			policy.onClassHierarchy(
				header.className,
				listOfNotNull(header.superClassName) + header.interfaceNames,
			)
		}
		return policy.decide(changedClassFiles)
	}

	private suspend fun relink(timeline: Timeline): Step {
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

			// aapt2 errors are the user's resources failing to build - a compile error
			// in the domain's sense, with aapt2's diagnostics attached.
			is DaemonReply.BuildFailed -> {
				Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
			}

			is DaemonReply.Failed -> {
				Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
			}
		}
	}

	/** Dispatches a code-bearing deploy on the policy's decision. */
	private suspend fun deployDecided(
		decision: DeployDecision,
		dexFile: File?,
		arscFile: File?,
		assets: AssetPackager.PackagedAssets?,
		reason: String,
		startedAt: Long,
		timeline: Timeline,
	): BuildOutcome =
		when (decision) {
			DeployDecision.Recreate -> {
				deployPayload(generations.next(), dexFile, arscFile, assets, reason, startedAt, timeline)
			}

			is DeployDecision.Restart -> {
				deployRestart(decision, dexFile, arscFile, assets, reason, startedAt, timeline)
			}

			is DeployDecision.RebuildProxyApp -> {
				// Deploying anyway would hot-swap on a runtime that cannot restart,
				// leaving a live service/provider on stale code. Refuse before the deploy;
				// the session manager routes this into the proxy app rebuild fallback.
				BuildOutcome.RequiresProxyAppRebuild(InvalidationReason.OUTDATED_BASELINE, decision.detail)
			}
		}

	private suspend fun deployPayload(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assets: AssetPackager.PackagedAssets?,
		reason: String,
		startedAt: Long,
		timeline: Timeline,
	): BuildOutcome {
		timeline.markDeploySent(clock())
		val result =
			deployRecovering(generation, dexFile, arscFile, assets?.zip, metadata(reason, assets, restart = false))
		return when (result) {
			is DeployResult.Reloaded -> {
				// t3: the proxy app's reportReloaded (fired from the recreated activity's
				// onResume) came back over the channel - the new code is live.
				reportTimeline(timeline.completed(generation, clock()))
				BuildOutcome.Success(generation, clock() - startedAt)
			}

			else -> {
				failureOf(result, generation)
			}
		}
	}

	/**
	 * The restart path (design contract section 4): deploy with restart metadata, let
	 * the runtime persist + ack + exit, confirm the binder death, relaunch the launcher
	 * proxy, then VERIFY the fresh process reconnected at the deployed generation.
	 * [DeployResult.Disconnected] before the ack means the process died around the
	 * payload - the persist may or may not have landed, so the relaunch proceeds and
	 * the reconnect check decides honestly: only a reconnect AT the new generation is
	 * a success; anything else is reported as the failure it is (claiming success
	 * while the app runs an older generation would be exactly the silent-stale lie
	 * the invariant forbids).
	 */
	private suspend fun deployRestart(
		restart: DeployDecision.Restart,
		dexFile: File?,
		arscFile: File?,
		assets: AssetPackager.PackagedAssets?,
		reason: String,
		startedAt: Long,
		timeline: Timeline,
	): BuildOutcome {
		val generation = generations.next()
		log.info(
			"Restart deploy of generation {}: {} {} changed",
			generation,
			restart.kind,
			restart.componentClass,
		)
		timeline.markDeploySent(clock())
		val result =
			deployRecovering(generation, dexFile, arscFile, assets?.zip, metadata(reason, assets, restart = true))
		when (result) {
			is DeployResult.Reloaded -> {
				if (!deploy.awaitDisconnect(restartDisconnectTimeoutMillis)) {
					// The runtime acked but kept running: it predates restart support and
					// hot-swapped instead - a live service may now be stale. A proxy app rebuild
					// reinstalls a current runtime; honesty over silence.
					return BuildOutcome.RequiresProxyAppRebuild(
						InvalidationReason.OUTDATED_BASELINE,
						"proxy app acknowledged a restart deploy but did not exit " +
							"(runtime predates restart support)",
					)
				}
			}

			DeployResult.Disconnected -> {
				Unit
			}

			else -> {
				return failureOf(result, generation)
			}
		}

		val packageName = proxyAppPackage
		// launcherActivity is null when the MAIN/LAUNCHER filter lives on an
		// <activity-alias> (icon-switching apps) rather than an <activity>: no activity
		// carries launcher=true. Passing null lets the launcher fall back to the package's
		// default launch intent, which resolves the alias the OS itself would launch.
		if (packageName == null || launcher?.launch(packageName, launcherActivity) != true) {
			// The process is gone and nothing runs stale code, but the loop is visibly
			// broken until the app is opened again (it then boots whatever persisted).
			return BuildOutcome.DeployFailure(
				"Proxy app restarted for ${restart.componentClass} but could not be relaunched; " +
					"open it manually to load the new code",
			)
		}
		val reconnectGeneration = deploy.awaitReconnect(restartReconnectTimeoutMillis)
		return when {
			reconnectGeneration == null -> {
				BuildOutcome.DeployFailure(
					"Proxy app was relaunched for ${restart.componentClass} but did not " +
						"reconnect within $restartReconnectTimeoutMillis ms; open it manually",
				)
			}

			reconnectGeneration < generation -> {
				// The payload did not survive the process death: the fresh process booted
				// an older generation. A proxy app rebuild rebuilds + reinstalls from current
				// sources, which converges every component honestly.
				BuildOutcome.RequiresProxyAppRebuild(
					InvalidationReason.OUTDATED_BASELINE,
					"proxy app relaunched at generation $reconnectGeneration instead of " +
						"$generation (restart payload did not persist)",
				)
			}

			else -> {
				// t3: the relaunched process reconnected AT the deployed generation - the
				// restart swap is verifiably live (a slower t3 than a hot swap: full process
				// relaunch, not an activity recreate).
				reportTimeline(timeline.completed(generation, clock()))
				BuildOutcome.Success(generation, clock() - startedAt, restarted = true)
			}
		}
	}

	/**
	 * One deploy attempt with the defect-#88 recovery. A proxy app rebuild reinstall kills the
	 * proxy-app process, and only the proxy app can re-establish the AIDL connection (the
	 * bind is outbound from its QuickBuildRuntime); without recovery every later deploy
	 * fails NotConnected until the user opens the app by hand. On [DeployResult.NotConnected],
	 * launch the app once via [launcher], wait a bounded [restartReconnectTimeoutMillis]
	 * for the rebind, and retry the deploy exactly once - no loops, so a hard-broken app
	 * costs one launch per deploy attempt, never a retry storm. Chosen over relaunching
	 * right after the proxy app rebuild itself (reliability doc option 2): this acts only when a
	 * deploy actually needs the app and never steals the foreground unasked.
	 */
	private suspend fun deployRecovering(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assetsZip: File?,
		metadataJson: String,
	): DeployResult {
		val first = deploy.deploy(generation, dexFile, arscFile, assetsZip, metadataJson)
		if (first != DeployResult.NotConnected) return first
		val packageName = proxyAppPackage ?: return first
		val relauncher = launcher ?: return first
		log.info("Deploy of generation {} found no connected proxy app; relaunching it once", generation)
		if (!relauncher.launch(packageName, launcherActivity)) return first
		if (deploy.awaitReconnect(restartReconnectTimeoutMillis) == null) return first
		return deploy.deploy(generation, dexFile, arscFile, assetsZip, metadataJson)
	}

	private fun metadata(
		reason: String,
		assets: AssetPackager.PackagedAssets?,
		restart: Boolean,
	): String =
		JsonObject()
			.apply {
				addProperty("entryActivity", entryActivity)
				add(
					"changedAssets",
					JsonArray().also { array ->
						assets?.relativePaths?.forEach(array::add)
					},
				)
				addProperty("reason", reason)
				// String per the runtime's MiniJson convention (it reads only strings).
				if (restart) addProperty("restart", "true")
			}.toString()

	private fun failureOf(
		result: DeployResult,
		generation: Long,
	): BuildOutcome =
		when (result) {
			is DeployResult.Crashed -> {
				BuildOutcome.DeployFailure(
					"Generation $generation crashed in the proxy app: ${result.stackSummary}",
				)
			}

			DeployResult.NotConnected -> {
				// Reached only after deployRecovering's one launch-and-retry (or with no
				// launcher wired): the remedy is the user's, not the infrastructure's.
				BuildOutcome.DeployFailure(
					"Proxy app is not connected. Relaunch your app to reconnect, then deploy again.",
				)
			}

			DeployResult.Disconnected -> {
				BuildOutcome.DeployFailure("Proxy app disconnected during deploy")
			}

			is DeployResult.TimedOut -> {
				BuildOutcome.DeployFailure(
					"Proxy app did not confirm generation $generation within ${result.timeoutMillis} ms",
				)
			}

			is DeployResult.Failed -> {
				BuildOutcome.DeployFailure(result.message)
			}

			is DeployResult.Reloaded -> {
				// Callers handle Reloaded before mapping failures; keep the mapping total.
				BuildOutcome.DeployFailure("unexpected Reloaded in failure mapping")
			}
		}

	private sealed interface Step {
		data class Ok(
			val file: File,
			val decision: DeployDecision,
		) : Step

		data class Fail(
			val outcome: BuildOutcome,
		) : Step
	}

	/**
	 * Accumulates one build's e2e stamps as it flows through the pipeline (safe as a plain
	 * object: the [org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor] contract is
	 * at-most-one build in flight). [trigger] is t0 from the request; [markCompileDone] and
	 * [markDeploySent] stamp t1/t2; [completed] mints the [E2eTimeline] with t3. A route with
	 * no compile never calls [markCompileDone], so [E2eTimeline.compileDone] falls back to
	 * deploySent - t1==t2, and compileMillis then measures relink+package (documented on
	 * [E2eTimeline]).
	 */
	private class Timeline(
		private val trigger: Long,
		private val scratchFsType: () -> String?,
	) {
		private var compileDone: Long? = null
		private var deploySent: Long = trigger
		private var steps = E2eTimeline.StepTimings()
		private var spans = E2eTimeline.HostSpans()
		private var counts = E2eTimeline.BuildCounts()

		fun markCompileDone(now: Long) {
			compileDone = now
		}

		fun markDeploySent(now: Long) {
			deploySent = now
		}

		fun recordScan(millis: Long) {
			spans = spans.copy(scanMillis = millis)
		}

		fun recordCompileRpc(millis: Long) {
			spans = spans.copy(compileRpcMillis = millis)
		}

		fun recordPolicy(millis: Long) {
			spans = spans.copy(policyMillis = millis)
		}

		fun recordDexRpc(millis: Long) {
			spans = spans.copy(dexRpcMillis = millis)
		}

		fun recordRelinkRpc(millis: Long) {
			spans = spans.copy(relinkRpcMillis = millis)
		}

		fun recordCompileSteps(
			kotlinMillis: Long?,
			javaMillis: Long?,
			stats: CompileStats?,
		) {
			steps =
				steps.copy(
					kotlinMillis = kotlinMillis,
					javaMillis = javaMillis,
					preSnapMillis = stats?.preSnapMillis,
					postSnapMillis = stats?.postSnapMillis,
					javaAbiSnapMillis = stats?.javaAbiSnapMillis,
				)
			counts =
				counts.copy(
					allSources = stats?.allSources,
					kotlinCompiled = stats?.kotlinToCompile,
					javaSources = stats?.javaSources,
					changedClasses = stats?.changedClasses,
					compileOrdinal = stats?.compileOrdinal,
				)
		}

		fun recordDexSteps(
			stripMillis: Long?,
			d8Millis: Long?,
			stats: DexStats?,
		) {
			steps = steps.copy(stripMillis = stripMillis, d8Millis = d8Millis)
			counts = counts.copy(classFiles = stats?.classFiles, classBytes = stats?.classBytes)
		}

		fun recordRelinkSteps(
			aapt2CompileMillis: Long?,
			aapt2LinkMillis: Long?,
		) {
			steps = steps.copy(aapt2CompileMillis = aapt2CompileMillis, aapt2LinkMillis = aapt2LinkMillis)
		}

		fun completed(
			generation: Long,
			reloadLive: Long,
		): E2eTimeline =
			E2eTimeline(
				generation = generation,
				trigger = trigger,
				compileDone = compileDone ?: deploySent,
				deploySent = deploySent,
				reloadLive = reloadLive,
				steps = steps.takeUnless { it.isEmpty() },
				spans = spans.takeUnless { it.isEmpty() },
				counts = counts.takeUnless { it.isEmpty() },
				scratchFsType = scratchFsType(),
			)
	}

	private companion object {
		private val log = LoggerFactory.getLogger(LiveReloadExecutorImpl::class.java)

		/**
		 * How long the runtime gets to exit after acking a restart deploy. Generous vs
		 * the ~ms it needs; hitting it at all means the runtime ignored the request.
		 */
		const val DEFAULT_RESTART_DISCONNECT_TIMEOUT_MILLIS = 5_000L

		/**
		 * How long the relaunched process gets to boot + bind + connect back. A cold
		 * app start on the mission's low-end hardware, so this mirrors the deploy
		 * verdict timeout rather than the exit timeout. Also bounds the rebind wait in
		 * [deployRecovering] - the same boot-bind-connect being waited on.
		 */
		const val DEFAULT_RESTART_RECONNECT_TIMEOUT_MILLIS = 15_000L
	}
}
