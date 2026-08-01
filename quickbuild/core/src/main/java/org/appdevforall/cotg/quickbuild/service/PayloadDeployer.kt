package org.appdevforall.cotg.quickbuild.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.appdevforall.cotg.quickbuild.data.AssetPackager
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.DeployDecision
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Deploys one build's artifacts to the proxy app, from the deploy decision onward:
 * hot swap vs process restart, the binder-death wait, the relaunch, the
 * reconnect-generation verification, the defect-#88 launch-and-retry, and the mapping
 * of every [DeployResult] to a [BuildOutcome]. The executor hands it artifacts; a
 * generation is allocated here, only once a deploy actually goes out.
 *
 * Call only on the session dispatcher; this class holds no scope of its own - its
 * suspend functions inherit the caller's confinement.
 */
internal class PayloadDeployer(
	private val deploy: DeploySender,
	private val generations: GenerationTracker,
	/** The user app's entry activity FQN, echoed to the runtime in payload metadata. */
	private val entryActivity: String,
	/** The installed proxy app's applicationId; restart relaunch target. */
	private val proxyAppPackage: String?,
	/**
	 * Launcher proxy activity FQN from the transformed manifest; the restart relaunch
	 * target. Null when the MAIN/LAUNCHER filter lives on an `<activity-alias>` (no
	 * proxied activity carries it) - the relaunch then falls back to the package's
	 * default launch intent.
	 */
	private val launcherActivity: String?,
	private val launcher: ProxyAppLauncher?,
	private val restartDisconnectTimeoutMillis: Long,
	private val restartReconnectTimeoutMillis: Long,
	private val clock: () -> Long,
	/** Hands a completed timeline to the executor's log + analytics channels. */
	private val reportTimeline: (E2eTimeline) -> Unit,
) {
	/** Dispatches a code-bearing deploy on the policy's decision. */
	suspend fun deploy(
		decision: DeployDecision,
		dexFile: File?,
		arscFile: File?,
		assets: AssetPackager.PackagedAssets?,
		reason: String,
		startedAt: Long,
		recorder: E2eTimelineRecorder,
	): BuildOutcome =
		when (decision) {
			DeployDecision.Recreate -> {
				deployPayload(generations.next(), dexFile, arscFile, assets, reason, startedAt, recorder)
			}

			is DeployDecision.Restart -> {
				deployRestart(decision, dexFile, arscFile, assets, reason, startedAt, recorder)
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
		recorder: E2eTimelineRecorder,
	): BuildOutcome {
		recorder.markDeploySent(clock())
		val result =
			deployRecovering(generation, dexFile, arscFile, assets?.zip, metadata(reason, assets, restart = false))
		return when (result) {
			is DeployResult.Reloaded -> {
				// t3: the proxy app's reportReloaded (fired from the recreated activity's
				// onResume) came back over the channel - the new code is live.
				reportTimeline(recorder.completed(generation, clock()))
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
		recorder: E2eTimelineRecorder,
	): BuildOutcome {
		val generation = generations.next()
		log.info(
			"Restart deploy of generation {}: {} {} changed",
			generation,
			restart.kind,
			restart.componentClass,
		)
		recorder.markDeploySent(clock())
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
				reportTimeline(recorder.completed(generation, clock()))
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

	private companion object {
		private val log = LoggerFactory.getLogger(PayloadDeployer::class.java)
	}
}
