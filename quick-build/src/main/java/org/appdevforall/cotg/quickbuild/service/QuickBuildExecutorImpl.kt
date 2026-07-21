package org.appdevforall.cotg.quickbuild.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import org.appdevforall.cotg.quickbuild.data.AssetPackager
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ClassHeader
import org.appdevforall.cotg.quickbuild.domain.DeployDecision
import org.appdevforall.cotg.quickbuild.domain.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildExecutor
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The warm-daemon pipeline behind the domain [QuickBuildExecutor] contract: routes a
 * classified changed-set through compile/dex/relink on the daemon, then deploys the
 * artifacts to the test app.
 *
 * Every failure becomes a [BuildOutcome] - nothing escapes to crash the orchestrator.
 * A generation is allocated only after the build steps succeed, so a compile error
 * burns nothing and the test app verifiably stays on its old generation.
 *
 * After a successful compile the [deployPolicy] decides hot swap vs process restart
 * (a recompiled service/provider/Application class cannot be swapped into a live
 * instance). The restart path deploys with `"restart": "true"` metadata - the runtime
 * persists the payload, acks, and exits - then waits for the binder death, relaunches
 * the launcher proxy via [launcher], and verifies the fresh process reconnected AT the
 * deployed generation before claiming success (see [deployRestart]).
 */
class QuickBuildExecutorImpl(
	private val daemon: QuickBuildDaemon,
	private val deploy: DeploySender,
	private val layout: QuickBuildProjectLayout,
	private val entryActivity: String,
	private val generations: GenerationTracker,
	/** Scratch dir for payload staging (the changed-assets zip). */
	private val workDir: File,
	/**
	 * Setup-build proxy classes, bundled into every payload dex — the manifest's proxy
	 * components extend user classes, so a payload without them cannot be loaded.
	 */
	private val proxyClassesDir: File? = null,
	/** The setup build's transformed manifest; relinks link against it when present. */
	private val testAppManifest: File? = null,
	/** Restart-vs-recreate decision; null (a session without one) always hot-swaps. */
	private val deployPolicy: DeployPolicy? = null,
	/** The installed test app's applicationId; restart relaunch target. */
	private val testAppPackage: String? = null,
	/**
	 * Launcher proxy activity FQN from the transformed manifest; the restart relaunch
	 * target. Null when the MAIN/LAUNCHER filter lives on an `<activity-alias>` (no
	 * proxied activity carries it) - the relaunch then falls back to the package's
	 * default launch intent.
	 */
	private val launcherActivity: String? = null,
	private val launcher: TestAppLauncher? = null,
	private val restartDisconnectTimeoutMillis: Long = DEFAULT_RESTART_DISCONNECT_TIMEOUT_MILLIS,
	private val restartReconnectTimeoutMillis: Long = DEFAULT_RESTART_RECONNECT_TIMEOUT_MILLIS,
	private val assetPackager: AssetPackager = AssetPackager(),
	private val clock: () -> Long = System::currentTimeMillis,
) : QuickBuildExecutor {
	override suspend fun execute(request: BuildRequest): BuildOutcome =
		try {
			executeInner(request).also(::notifyTestApp)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.error("Quick build #{} pipeline failure", request.buildId, e)
			BuildOutcome.InfrastructureFailure(e.message ?: e.javaClass.name)
		}

	/**
	 * The test-app half of the never-stale invariant (plan A1): a compile error never
	 * produces a payload, so without this message the app would keep running old code
	 * with no on-screen signal. Success clears a previously shown failure (also on the
	 * no-payload success paths, e.g. an unforced no-op). Best-effort by contract.
	 */
	private fun notifyTestApp(outcome: BuildOutcome) {
		try {
			when (outcome) {
				is BuildOutcome.CompileError ->
					deploy.notifyBuildStatus(BuildStatusJson.buildFailed(outcome.diagnostics))
				is BuildOutcome.Success -> deploy.notifyBuildStatus(BuildStatusJson.buildOk())
				// Deploy/infrastructure failures surface in CoGo's own status UI; the test
				// app cannot say anything more truthful than what it already shows. A
				// RequiresRebaseline surfaces through the session's fallback flow.
				else -> Unit
			}
		} catch (e: Exception) {
			// Best-effort messaging must never rewrite a real outcome (a throw here
			// would turn e.g. a CompileError into an InfrastructureFailure upstream).
			log.warn("Build-status notification failed", e)
		}
	}

	private suspend fun executeInner(request: BuildRequest): BuildOutcome {
		val startedAt = clock()

		val knownFiles = (request.changes as? ChangedFiles.Known)?.files ?: emptySet()
		val assets =
			assetPackager.packageAssets(
				changedFiles = knownFiles,
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
					// test app running the gen-0 baseline). A metadata-only replay of the
					// current generation cannot work: the runtime only accepts strictly
					// NEWER generations (an equal one is dropped without a report), and a
					// null-dex payload at a newer generation would advance the app's
					// generation without shipping the classes it claims - a stale-code lie.
					val dex = compileAndDex(ChangedFiles.Unknown)
					if (dex is Step.Fail) return dex.outcome
					val arsc = relink()
					if (arsc is Step.Fail) return arsc.outcome
					deployDecided(
						(dex as Step.Ok).decision,
						dex.file,
						(arsc as Step.Ok).file,
						assets,
						"forced",
						startedAt,
					)
				}
			}

			BuildRoute.CodeOnly -> {
				val dex = compileAndDex(request.changes)
				when (dex) {
					is Step.Fail -> dex.outcome
					is Step.Ok ->
						deployDecided(dex.decision, dex.file, null, assets, "code", startedAt)
				}
			}

			BuildRoute.ResourcesOnly -> {
				// Resource-only deploys never restart: no code moved.
				when (val arsc = relink()) {
					is Step.Fail -> arsc.outcome
					is Step.Ok ->
						deployPayload(generations.next(), null, arsc.file, assets, "resources", startedAt)
				}
			}

			BuildRoute.CodeAndResources -> {
				val dex = compileAndDex(request.changes)
				if (dex is Step.Fail) return dex.outcome
				val arsc = relink()
				if (arsc is Step.Fail) return arsc.outcome
				deployDecided(
					(dex as Step.Ok).decision,
					dex.file,
					(arsc as Step.Ok).file,
					assets,
					"mixed",
					startedAt,
				)
			}

			BuildRoute.AssetsOnly -> {
				if (assets == null) {
					// Classifier said assets-only but nothing packaged (e.g. the only
					// change was a deletion of a file already gone). Nothing to ship.
					BuildOutcome.Success(generations.current, clock() - startedAt)
				} else {
					deployPayload(generations.next(), null, null, assets, "assets", startedAt)
				}
			}

			is BuildRoute.FullGradleBuild ->
				// Contract: the orchestrator never routes this here. Refuse honestly.
				BuildOutcome.InfrastructureFailure(
					"FullGradleBuild route must not reach the quick path",
				)
		}
	}

	/**
	 * Compile then dex; [ChangedFiles.Unknown] recompiles everything (IC re-seed).
	 * On success also feeds the compile's changed class headers into the policy's
	 * supertype index (catches re-parenting) and computes the deploy decision.
	 */
	private suspend fun compileAndDex(changes: ChangedFiles): Step {
		val allSources = layout.allSources()
		val changedSources =
			when (changes) {
				ChangedFiles.Unknown -> allSources
				is ChangedFiles.Known ->
					changes.files.filter { it.extension == "kt" || it.extension == "java" }
			}

		val compiled =
			when (val reply = daemon.compile(allSources, changedSources)) {
				is DaemonReply.Ok -> reply.value
				is DaemonReply.BuildFailed -> return Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
				is DaemonReply.Failed ->
					return Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
			}

		val decision = decideDeploy(compiled.classesDir, compiled.changedClassFiles)

		return when (val reply = daemon.dex(listOfNotNull(compiled.classesDir, proxyClassesDir))) {
			is DaemonReply.Ok -> Step.Ok(reply.value, decision)
			is DaemonReply.BuildFailed -> Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
			is DaemonReply.Failed ->
				Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
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

	private suspend fun relink(): Step =
		when (val reply = daemon.relink(layout.resDirs(), testAppManifest ?: layout.manifest())) {
			is DaemonReply.Ok -> Step.Ok(reply.value, DeployDecision.Recreate)
			// aapt2 errors are the user's resources failing to build - a compile error
			// in the domain's sense, with aapt2's diagnostics attached.
			is DaemonReply.BuildFailed -> Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
			is DaemonReply.Failed ->
				Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
		}

	/** Dispatches a code-bearing deploy on the policy's decision. */
	private suspend fun deployDecided(
		decision: DeployDecision,
		dexFile: File?,
		arscFile: File?,
		assets: AssetPackager.PackagedAssets?,
		reason: String,
		startedAt: Long,
	): BuildOutcome =
		when (decision) {
			DeployDecision.Recreate ->
				deployPayload(generations.next(), dexFile, arscFile, assets, reason, startedAt)
			is DeployDecision.Restart ->
				deployRestart(decision, dexFile, arscFile, assets, reason, startedAt)
			is DeployDecision.Rebaseline ->
				// Deploying anyway would hot-swap on a runtime that cannot restart,
				// leaving a live service/provider on stale code. Refuse before the deploy;
				// the session manager routes this into the rebaseline fallback.
				BuildOutcome.RequiresRebaseline(InvalidationReason.OUTDATED_BASELINE, decision.detail)
		}

	private suspend fun deployPayload(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assets: AssetPackager.PackagedAssets?,
		reason: String,
		startedAt: Long,
	): BuildOutcome {
		val result =
			deploy.deploy(generation, dexFile, arscFile, assets?.zip, metadata(reason, assets, restart = false))
		return when (result) {
			is DeployResult.Reloaded -> BuildOutcome.Success(generation, clock() - startedAt)
			else -> failureOf(result, generation)
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
	): BuildOutcome {
		val generation = generations.next()
		log.info(
			"Restart deploy of generation {}: {} {} changed",
			generation,
			restart.kind,
			restart.componentClass,
		)
		val result =
			deploy.deploy(generation, dexFile, arscFile, assets?.zip, metadata(reason, assets, restart = true))
		when (result) {
			is DeployResult.Reloaded ->
				if (!deploy.awaitDisconnect(restartDisconnectTimeoutMillis)) {
					// The runtime acked but kept running: it predates restart support and
					// hot-swapped instead - a live service may now be stale. Rebaseline
					// reinstalls a current runtime; honesty over silence.
					return BuildOutcome.RequiresRebaseline(
						InvalidationReason.OUTDATED_BASELINE,
						"test app acknowledged a restart deploy but did not exit " +
							"(runtime predates restart support)",
					)
				}
			DeployResult.Disconnected -> Unit
			else -> return failureOf(result, generation)
		}

		val packageName = testAppPackage
		// launcherActivity is null when the MAIN/LAUNCHER filter lives on an
		// <activity-alias> (icon-switching apps) rather than an <activity>: no activity
		// carries launcher=true. Passing null lets the launcher fall back to the package's
		// default launch intent, which resolves the alias the OS itself would launch.
		if (packageName == null || launcher?.launch(packageName, launcherActivity) != true) {
			// The process is gone and nothing runs stale code, but the loop is visibly
			// broken until the app is opened again (it then boots whatever persisted).
			return BuildOutcome.DeployFailure(
				"Test app restarted for ${restart.componentClass} but could not be relaunched; " +
					"open it manually to load the new code",
			)
		}
		val reconnectGeneration = deploy.awaitReconnect(restartReconnectTimeoutMillis)
		return when {
			reconnectGeneration == null ->
				BuildOutcome.DeployFailure(
					"Test app was relaunched for ${restart.componentClass} but did not " +
						"reconnect within ${restartReconnectTimeoutMillis} ms; open it manually",
				)
			reconnectGeneration < generation ->
				// The payload did not survive the process death: the fresh process booted
				// an older generation. A rebaseline rebuilds + reinstalls from current
				// sources, which converges every component honestly.
				BuildOutcome.RequiresRebaseline(
					InvalidationReason.OUTDATED_BASELINE,
					"test app relaunched at generation $reconnectGeneration instead of " +
						"$generation (restart payload did not persist)",
				)
			else -> BuildOutcome.Success(generation, clock() - startedAt, restarted = true)
		}
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
			is DeployResult.Crashed ->
				BuildOutcome.DeployFailure(
					"Generation $generation crashed in the test app: ${result.stackSummary}",
				)
			DeployResult.NotConnected ->
				BuildOutcome.DeployFailure("Test app is not connected")
			DeployResult.Disconnected ->
				BuildOutcome.DeployFailure("Test app disconnected during deploy")
			is DeployResult.TimedOut ->
				BuildOutcome.DeployFailure(
					"Test app did not confirm generation $generation within ${result.timeoutMillis} ms",
				)
			is DeployResult.Failed -> BuildOutcome.DeployFailure(result.message)
			is DeployResult.Reloaded ->
				// Callers handle Reloaded before mapping failures; keep the mapping total.
				BuildOutcome.DeployFailure("unexpected Reloaded in failure mapping")
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

	private companion object {
		private val log = LoggerFactory.getLogger(QuickBuildExecutorImpl::class.java)

		/**
		 * How long the runtime gets to exit after acking a restart deploy. Generous vs
		 * the ~ms it needs; hitting it at all means the runtime ignored the request.
		 */
		const val DEFAULT_RESTART_DISCONNECT_TIMEOUT_MILLIS = 5_000L

		/**
		 * How long the relaunched process gets to boot + bind + connect back. A cold
		 * app start on the mission's low-end hardware, so this mirrors the deploy
		 * verdict timeout rather than the exit timeout.
		 */
		const val DEFAULT_RESTART_RECONNECT_TIMEOUT_MILLIS = 15_000L
	}
}
