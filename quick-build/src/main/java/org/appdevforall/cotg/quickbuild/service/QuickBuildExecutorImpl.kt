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
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
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
				// app cannot say anything more truthful than what it already shows.
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
					deployPayload(
						generations.next(),
						(dex as Step.Ok).file,
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
						deployPayload(generations.next(), dex.file, null, assets, "code", startedAt)
				}
			}

			BuildRoute.ResourcesOnly -> {
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
				deployPayload(
					generations.next(),
					(dex as Step.Ok).file,
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

	/** Compile then dex; [ChangedFiles.Unknown] recompiles everything (IC re-seed). */
	private suspend fun compileAndDex(changes: ChangedFiles): Step {
		val allSources = layout.allSources()
		val changedSources =
			when (changes) {
				ChangedFiles.Unknown -> allSources
				is ChangedFiles.Known ->
					changes.files.filter { it.extension == "kt" || it.extension == "java" }
			}

		val classesDir =
			when (val reply = daemon.compile(allSources, changedSources)) {
				is DaemonReply.Ok -> reply.value
				is DaemonReply.BuildFailed -> return Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
				is DaemonReply.Failed ->
					return Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
			}

		return when (val reply = daemon.dex(listOfNotNull(classesDir, proxyClassesDir))) {
			is DaemonReply.Ok -> Step.Ok(reply.value)
			is DaemonReply.BuildFailed -> Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
			is DaemonReply.Failed ->
				Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
		}
	}

	private suspend fun relink(): Step =
		when (val reply = daemon.relink(layout.resDirs(), testAppManifest ?: layout.manifest())) {
			is DaemonReply.Ok -> Step.Ok(reply.value)
			// aapt2 errors are the user's resources failing to build - a compile error
			// in the domain's sense, with aapt2's diagnostics attached.
			is DaemonReply.BuildFailed -> Step.Fail(BuildOutcome.CompileError(reply.diagnostics))
			is DaemonReply.Failed ->
				Step.Fail(BuildOutcome.InfrastructureFailure(reply.message, reply.daemonDied))
		}

	private suspend fun deployPayload(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assets: AssetPackager.PackagedAssets?,
		reason: String,
		startedAt: Long,
	): BuildOutcome {
		val metadata =
			JsonObject().apply {
				addProperty("entryActivity", entryActivity)
				add(
					"changedAssets",
					JsonArray().also { array ->
						assets?.relativePaths?.forEach(array::add)
					},
				)
				addProperty("reason", reason)
			}

		val result = deploy.deploy(generation, dexFile, arscFile, assets?.zip, metadata.toString())
		return when (result) {
			is DeployResult.Reloaded -> BuildOutcome.Success(generation, clock() - startedAt)
			is DeployResult.Crashed ->
				BuildOutcome.DeployFailure(
					"Generation $generation crashed in the test app: ${result.stackSummary}",
				)
			DeployResult.NotConnected ->
				BuildOutcome.DeployFailure("Test app is not connected")
			is DeployResult.TimedOut ->
				BuildOutcome.DeployFailure(
					"Test app did not confirm generation $generation within ${result.timeoutMillis} ms",
				)
			is DeployResult.Failed -> BuildOutcome.DeployFailure(result.message)
		}
	}

	private sealed interface Step {
		data class Ok(
			val file: File,
		) : Step

		data class Fail(
			val outcome: BuildOutcome,
		) : Step
	}

	private companion object {
		private val log = LoggerFactory.getLogger(QuickBuildExecutorImpl::class.java)
	}
}
