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
 * Gets one build's artifacts into the running proxy app, and reports honestly whether
 * they are live.
 *
 * Owns everything downstream of the deploy decision: hot swap versus process restart,
 * the relaunch and reconnect checks, the retry when no app is connected, and the mapping
 * of each [DeployResult] to a [BuildOutcome]. Generations are allocated here, so a build
 * that never deploys never burns one. Call only on the session dispatcher; the suspend
 * functions inherit the caller's confinement.
 */
internal class PayloadDeployer(
	/** Deploy channel to the bound proxy app; every wait it exposes is already bounded. */
	private val deploy: DeploySender,
	/** Generation allocator, pulled from only on a path that actually sends a payload. */
	private val generations: GenerationTracker,
	/** The user app's entry activity FQN, echoed to the runtime in payload metadata. */
	private val entryActivity: String,
	/** The installed proxy app's applicationId; restart relaunch target. */
	private val proxyAppPackage: String?,
	/**
	 * Launcher proxy activity FQN from the transformed manifest, the restart relaunch
	 * target. Null when the MAIN/LAUNCHER filter sits on an `<activity-alias>` that no
	 * proxied activity carries; the relaunch then uses the package's default launch
	 * intent.
	 */
	private val launcherActivity: String?,
	/** Relaunches the app. Null makes both the restart path and the retry fail honestly. */
	private val launcher: ProxyAppLauncher?,
	/** How long the runtime gets to exit after acking a restart deploy. */
	private val restartDisconnectTimeoutMillis: Long,
	/** How long a relaunched app gets to boot, bind, and report its generation. */
	private val restartReconnectTimeoutMillis: Long,
	/** Monotonic clock; must be the same one the timeline's earlier stamps came from. */
	private val clock: () -> Long,
	/** Hands a completed timeline to the executor's log + analytics channels. */
	private val reportTimeline: (E2eTimeline) -> Unit,
	/**
	 * Whether the build being deployed answers a Quick Build tap. Read at the moment a launch
	 * would happen rather than captured up front, because a tap can promote a build that is
	 * already in flight.
	 *
	 * Starting an activity always takes the screen, so this is what separates a deploy that may
	 * bring the app forward from one that must not: a save is not permission to interrupt
	 * someone who is still typing.
	 */
	private val userInitiated: () -> Boolean = { true },
) {
	/**
	 * Deploys one build's artifacts by the route [decision] chose, and reports the
	 * outcome.
	 *
	 * @param decision hot swap, process restart, or a refusal that needs a proxy app rebuild
	 * @param dexFile the payload's classes, or null when no code moved
	 * @param arscFile the relinked resource APK, or null when resources did not move
	 * @param assets the packaged changed assets, or null when no asset changed
	 * @param reason short route tag echoed to the runtime in metadata, for its own logging
	 * @param startedAt the build's start, used only for the reported duration
	 * @param recorder mutated in place; stamped with t2 here and t3 once the app confirms
	 * @return the outcome for the orchestrator; a generation is burned only on a path that
	 *   actually sends a payload
	 */
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
				// leaving a live service or provider on stale code. The session manager
				// routes this refusal into the proxy app rebuild fallback.
				BuildOutcome.RequiresProxyAppRebuild(InvalidationReason.OUTDATED_BASELINE, decision.detail)
			}
		}

	/**
	 * Hot-swap path: send the payload and let the running process recreate itself.
	 *
	 * @param generation the already-allocated generation this payload claims
	 * @param dexFile the payload's classes, or null when no code moved
	 * @param arscFile the relinked resource APK, or null when resources did not move
	 * @param assets the packaged changed assets, or null when no asset changed
	 * @param reason short route tag echoed to the runtime in metadata
	 * @param startedAt the build's start, used only for the reported duration
	 * @param recorder stamped with t2 before the send and t3 once the app reports back
	 * @return success only when the app confirmed the reload; every other result becomes a
	 *   deploy failure
	 */
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
		val recovered =
			deployRecovering(generation, dexFile, arscFile, assets?.zip, metadata(reason, assets, restart = false))
		return when (val result = recovered.result) {
			is DeployResult.Reloaded -> {
				// t3: reportReloaded came back from the recreated activity's onResume,
				// so the new code is live.
				reportTimeline(recorder.completed(generation, clock()))
				BuildOutcome.Success(generation, clock() - startedAt)
			}

			else -> {
				failureOf(result, generation, recovered.launched)
			}
		}
	}

	/**
	 * Restart path (design contract section 4): deploy with restart metadata, wait for
	 * the runtime to persist and exit, relaunch it, then check which generation came
	 * back.
	 *
	 * Only a reconnect at the deployed generation counts as success; anything lower means the
	 * payload was lost. A disconnect before the ack proceeds to relaunch, which settles it.
	 *
	 * @param restart the decision, whose component class names the thing that forced a
	 *   restart and appears in every message this path produces
	 * @param dexFile the payload's classes, or null when no code moved
	 * @param arscFile the relinked resource APK, or null when resources did not move
	 * @param assets the packaged changed assets, or null when no asset changed
	 * @param reason short route tag echoed to the runtime in metadata
	 * @param startedAt the build's start, used only for the reported duration
	 * @param recorder stamped with t2 before the send and t3 once the app reconnects
	 * @return success only on a reconnect at the deployed generation; a runtime that acked
	 *   but never exited comes back as a proxy-app-rebuild requirement
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
		val recovered =
			deployRecovering(generation, dexFile, arscFile, assets?.zip, metadata(reason, assets, restart = true))
		when (val result = recovered.result) {
			is DeployResult.Reloaded -> {
				if (!deploy.awaitDisconnect(restartDisconnectTimeoutMillis)) {
					// The runtime acked but kept running, so it predates restart support
					// and hot-swapped instead, leaving a live service possibly stale. A
					// proxy app rebuild reinstalls a current runtime.
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
				return failureOf(result, generation, recovered.launched)
			}
		}

		val packageName = proxyAppPackage
		// A null launcherActivity is expected for alias-launched apps; the launcher then
		// falls back to the default launch intent, which resolves the same alias the OS
		// would.
		if (packageName == null || launcher?.launch(packageName, launcherActivity) != true) {
			// The process is gone so nothing runs stale code, but the loop stays broken
			// until the user opens the app again.
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
				// The payload did not survive the process death, so the fresh process
				// booted an older generation. A proxy app rebuild reinstalls from
				// current sources and brings every component back in step.
				BuildOutcome.RequiresProxyAppRebuild(
					InvalidationReason.OUTDATED_BASELINE,
					"proxy app relaunched at generation $reconnectGeneration instead of " +
						"$generation (restart payload did not persist)",
				)
			}

			else -> {
				// t3: the relaunched process reconnected at the deployed generation, so
				// the restart swap is live. Slower than a hot swap by a full process
				// launch.
				reportTimeline(recorder.completed(generation, clock()))
				BuildOutcome.Success(generation, clock() - startedAt, restarted = true)
			}
		}
	}

	/**
	 * Deploys once, and on [DeployResult.NotConnected] launches the app once and retries.
	 *
	 * A proxy app reinstall kills the process, and only the proxy app can re-establish the
	 * AIDL connection, so without this every later deploy fails until the user opens it by
	 * hand. Exactly one launch and one retry, so a hard-broken app never becomes a retry
	 * storm and the foreground is taken only when a deploy needs it.
	 *
	 * @param generation the already-allocated generation both attempts claim; the retry
	 *   must not allocate a second one
	 * @param dexFile the payload's classes, or null when no code moved
	 * @param arscFile the relinked resource APK, or null when resources did not move
	 * @param assetsZip the changed-assets archive, or null when no asset changed
	 * @param metadataJson the metadata built for this route, reused verbatim on the retry
	 * @return the second attempt's result, or the first when no retry was possible
	 */
	private suspend fun deployRecovering(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assetsZip: File?,
		metadataJson: String,
	): RecoveredDeploy {
		val first = deploy.deploy(generation, dexFile, arscFile, assetsZip, metadataJson)
		if (first != DeployResult.NotConnected) return RecoveredDeploy(first, launched = false)
		if (!userInitiated()) {
			// A save must not open the app. Nobody asked for it, and starting an activity
			// would pull the user out of the editor mid-edit. The failure is honest - there
			// was nowhere to deploy - and the next tap launches the app and deploys then.
			log.info("Deploy of generation {} found no connected proxy app; not launching it unasked", generation)
			return RecoveredDeploy(first, launched = false)
		}
		val packageName = proxyAppPackage ?: return RecoveredDeploy(first, launched = false)
		val relauncher = launcher ?: return RecoveredDeploy(first, launched = false)
		log.info("Deploy of generation {} found no connected proxy app; relaunching it once", generation)
		// A launch that never started is not evidence the app cannot stay up - nothing ran
		// to fail. Only a started app that then fails to come back counts.
		if (!relauncher.launch(packageName, launcherActivity)) return RecoveredDeploy(first, launched = false)
		if (deploy.awaitReconnect(restartReconnectTimeoutMillis) == null) {
			return RecoveredDeploy(first, launched = true)
		}
		return RecoveredDeploy(
			deploy.deploy(generation, dexFile, arscFile, assetsZip, metadataJson),
			launched = true,
		)
	}

	/**
	 * A deploy attempt plus whether this call actually started the proxy app.
	 *
	 * [launched] is what separates "nobody has opened your app" from "your app will not
	 * stay up": it is true only when a launch really started the app and it still did not
	 * come back, which is the evidence a repeat escalates into the cannot-stay-up dialog.
	 * A save never launches, and a launch that failed to start never ran, so neither
	 * counts.
	 *
	 * @property result the verdict to report
	 * @property launched true only when the app was started and still failed to reconnect
	 *   or deploy
	 */
	private data class RecoveredDeploy(
		val result: DeployResult,
		val launched: Boolean,
	)

	/**
	 * Builds the payload metadata the runtime reads.
	 *
	 * The field set is defined by the two ends and nowhere else: this builder writes it, and the
	 * runtime's `DeployMetadata` parses it. Adding a field here needs a matching read there.
	 *
	 * @param reason short route tag, for the runtime's own logging only
	 * @param assets supplies the changed-asset paths; null yields an empty array, which is
	 *   how the runtime is told no asset moved
	 * @param restart true to ask the runtime to persist and exit rather than hot-swap
	 * @return the metadata JSON, every value a string per the runtime's MiniJson parser
	 */
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

	/**
	 * Turns a non-reloaded [DeployResult] into the outcome the user sees.
	 *
	 * @param result the deploy verdict; [DeployResult.Reloaded] is a caller error and maps
	 *   to a failure rather than throwing, to keep the mapping total
	 * @param generation the generation the failed payload claimed, named in the message so
	 *   the user can tell one failed deploy from another
	 * @param launchAttempted whether this deploy actually started the proxy app - see
	 *   [RecoveredDeploy.launched]. No default: guessing it from [userInitiated] once
	 *   flagged every tap that never got as far as launching, which is what feeds the
	 *   cannot-stay-up dialog.
	 * @return the deploy failure, with remediation text wherever the user can act
	 */
	private fun failureOf(
		result: DeployResult,
		generation: Long,
		launchAttempted: Boolean,
	): BuildOutcome =
		when (result) {
			is DeployResult.Crashed -> {
				BuildOutcome.DeployFailure(
					"Generation $generation crashed in the proxy app: ${result.stackSummary}",
				)
			}

			DeployResult.NotConnected -> {
				// proxyAppNotConnected means "we launched it and it still is not there",
				// which is the evidence a repeat turns into the cannot-stay-up dialog. A
				// save deliberately never launches, so flagging it here would accuse a
				// perfectly healthy app of crashing just because nobody has opened it.
				BuildOutcome.DeployFailure(
					"Your app is not running. Tap Quick Build to start it with your changes.",
					proxyAppNotConnected = launchAttempted,
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
		private val log = LoggerFactory.getLogger("QB-PayloadDeployer")
	}
}
