package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.annotation.StringRes
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.tooling.api.GradlePluginConfig
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.quickbuild.data.FileGenerationStore
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.reload.RealIdInstall
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.service.provision.InstallOutcome
import org.appdevforall.cotg.quickbuild.service.provision.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppInstaller
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppRebuildOutcome
import org.appdevforall.cotg.quickbuild.service.provision.QuickBuildProvisioner
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Why a proxy app build is running, which fixes whether it stamps a fresh baseline generation
 * (concurrency.md rule 2). A pure mapping so a test can pin every call site's choice: flipping
 * a provision or rebaseline to unstamped re-creates S7 (the installed baseline is no longer
 * strictly older than every later deploy), and flipping the prebuild to stamped burns a
 * generation and re-runs the packaging tail on every project open.
 */
internal enum class ProxyAppBuildPurpose(
	/** Allocate the next generation from the project's persistent counter and stamp the APK. */
	val stampBaseline: Boolean,
) {
	/** The first provision; its APK is installed, so it stamps. */
	PROVISION(true),

	/** The eager warm-up; its APK is never installed, so it must not stamp. */
	PREBUILD(false),

	/** A rebaseline; its APK is reinstalled, so it stamps. */
	REBASELINE(true),
}

/**
 * Real-Gradle side of quick-build provisioning: stages the bundled artifacts, runs the proxy app
 * build through [BuildService.executeTasks], reads the report the Gradle plugin writes, and hands
 * the proxy app to [installer]. It installs under the project's real applicationId, so before
 * installing over an existing package it checks the built signing cert against the installed one
 * and refuses loud on a mismatch rather than clobbering a third-party install.
 */
class GradleQuickBuildProvisioner(
	private val context: Context,
	private val paths: EnvironmentQuickBuildPaths,
	private val installer: ProxyAppInstaller,
	private val packages: InstalledPackages,
	/** SHA-256 of an APK file's signing cert; app wiring uses PackageManager. */
	private val apkCertSha256: (File) -> String? = { null },
	/**
	 * The Build Output narrator, so a failed proxy app build can quote Gradle. Null in tests,
	 * which only costs the quote.
	 */
	private val narrator: QuickBuildOutputNarrator? = null,
	/**
	 * Allocates the generation stamped into a provision/rebaseline build, from the SAME
	 * persistent per-project counter hot deploys draw from - only that keeps later deploys
	 * strictly newer than the installed baseline. Allocation persists before the number is
	 * handed out, so a failed build burns it (monotonic counters may skip). Injectable for
	 * tests.
	 */
	private val nextBaselineGeneration: (File) -> Long = { projectRoot ->
		GenerationTracker(FileGenerationStore.forProject(projectRoot)).next()
	},
) : QuickBuildProvisioner {
	override suspend fun provision(): ProvisionOutcome {
		unsupportedProjectTypeFailure()?.let { return ProvisionOutcome.Failure(QuickBuildMessage.Literal(context.getString(it))) }

		// A busy Gradle slot folds into the same failure as any other: from Idle the next tap
		// re-provisions, so there is no parked state to defer into (unlike [rebuildProxyApp]).
		val buildResult =
			when (val built = runProxyAppBuild(ProxyAppBuildPurpose.PROVISION)) {
				is ProxyAppBuildResult.Ready -> {
					built
				}

				is ProxyAppBuildResult.Failed -> {
					return ProvisionOutcome.Failure(
						built.message?.let(QuickBuildMessage::Literal)
							?: QuickBuildMessage.Literal(context.getString(R.string.quick_build_setup_failed)),
					)
				}

				ProxyAppBuildResult.SlotBusy -> {
					return ProvisionOutcome.Failure(QuickBuildMessage.Literal(context.getString(R.string.quick_build_setup_failed)))
				}
			}
		val (proxyApp, projectRoot, moduleDir) = buildResult

		QuickBuildProjectSupport
			.noLaunchableActivityMessage(proxyApp.entryActivity)
			?.let { return ProvisionOutcome.Failure(QuickBuildMessage.Literal(context.getString(it))) }
		installRefusal(proxyApp)?.let { return ProvisionOutcome.Failure(it) }

		val uid =
			when (val installed = installer.ensureInstalled(proxyApp.apk, proxyApp.proxyAppPackage)) {
				is InstallOutcome.Failed -> {
					return ProvisionOutcome.Failure(installed.message)
				}

				// From Idle the next tap re-provisions (fast: tasks up-to-date), so the
				// existing failure surface already IS the retry offer here.
				is InstallOutcome.ConfirmationNotGiven -> {
					return ProvisionOutcome.Failure(
						initialProvisionMessageOverride(installed)
							?.let { QuickBuildMessage.Literal(context.getString(it)) }
							?: installed.message,
					)
				}

				is InstallOutcome.Installed -> {
					installed.uid
				}
			}

		return ProvisionOutcome.Success(
			proxyApp = proxyApp,
			proxyAppUid = uid,
			layout =
				QuickBuildProjectLayout(
					projectRoot = projectRoot,
					appModuleDir = moduleDir,
					classpath = proxyApp.classpath,
					extraSourceRoots = proxyApp.sourceRoots,
					stableIdsFile = proxyApp.stableIdsFile,
					libraryResourceFlats = proxyApp.libraryResourceFlats,
				),
			variantName = buildResult.variantName,
			baselineGeneration = buildResult.baselineGeneration,
		)
	}

	override suspend fun prebuildProxyApp() {
		// Eager warm-up: run the proxy app build, install nothing - nothing reaches the
		// device before the user confirms, so no clobber can happen. The tap-time
		// provision() re-runs it against current disk (fast: tasks up-to-date), so a
		// stale warm result can never become the session baseline.
		if (unsupportedProjectTypeFailure() != null) {
			log.warn("Quick Build unsupported for this project type; skipping the proxy app prebuild")
			return
		}
		// PREBUILD does not stamp: this APK is never installed, and burning a fresh stamp on
		// every project open would re-run the packaging tail the warm-up exists to pre-pay.
		if (runProxyAppBuild(ProxyAppBuildPurpose.PREBUILD) !is ProxyAppBuildResult.Ready) {
			log.warn("Eager quick-build proxy app build did not complete; the first tap retries")
		}
	}

	override suspend fun rebuildProxyApp(): ProxyAppRebuildOutcome {
		unsupportedProjectTypeFailure()?.let { return ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal(context.getString(it))) }

		val buildResult =
			when (val built = runProxyAppBuild(ProxyAppBuildPurpose.REBASELINE)) {
				is ProxyAppBuildResult.Ready -> {
					built
				}

				// Nothing ran, so this is not a build failure: the session parks back and
				// retries later WITHOUT spending its bounded auto-retry budget.
				ProxyAppBuildResult.SlotBusy -> {
					return ProxyAppRebuildOutcome.BuildSlotBusy
				}

				is ProxyAppBuildResult.Failed -> {
					return ProxyAppRebuildOutcome.Failure(
						built.message?.let(QuickBuildMessage::Literal)
							?: QuickBuildMessage.RebuildFailed,
					)
				}
			}

		QuickBuildProjectSupport
			.noLaunchableActivityMessage(buildResult.proxyApp.entryActivity)
			?.let { return ProxyAppRebuildOutcome.Failure(QuickBuildMessage.Literal(context.getString(it))) }
		installRefusal(buildResult.proxyApp)?.let { return ProxyAppRebuildOutcome.Failure(it) }

		// The installer skips when the rebuilt APK is byte-identical to what is
		// installed (common when a gradle edit did not change the proxy app), so a
		// proxy app rebuild only re-prompts the user when the APK really changed.
		return when (
			val installed =
				installer.ensureInstalled(buildResult.proxyApp.apk, buildResult.proxyApp.proxyAppPackage)
		) {
			is InstallOutcome.Failed -> {
				ProxyAppRebuildOutcome.Failure(installed.message)
			}

			is InstallOutcome.ConfirmationNotGiven -> {
				// The rebuilt APK is good; only the user's confirmation is missing (no
				// dialog shown / cancelled / left untapped - the message says which).
				// Kept distinguishable so the session can offer a retry instead of
				// stranding itself at Idle.
				ProxyAppRebuildOutcome.InstallNotConfirmed(installed.message)
			}

			is InstallOutcome.Installed -> {
				ProxyAppRebuildOutcome.Success(
					proxyApp = buildResult.proxyApp,
					baselineGeneration = buildResult.baselineGeneration,
					layout =
						QuickBuildProjectLayout(
							projectRoot = buildResult.projectRoot,
							appModuleDir = buildResult.moduleDir,
							classpath = buildResult.proxyApp.classpath,
							extraSourceRoots = buildResult.proxyApp.sourceRoots,
							stableIdsFile = buildResult.proxyApp.stableIdsFile,
							libraryResourceFlats = buildResult.proxyApp.libraryResourceFlats,
						),
				)
			}
		}
	}

	/**
	 * Quick Build can't provision a plugin project (its artifact is a `.cgp`, not a
	 * runnable app) - checked up front so this fails fast with a friendly message
	 * instead of a raw Gradle `TaskSelectionException` from the proxy app build.
	 */
	@StringRes
	private fun unsupportedProjectTypeFailure(): Int? =
		QuickBuildProjectSupport.unsupportedProjectTypeMessage(
			IProjectManager.getInstance().isPluginProject(),
		)

	/**
	 * The authoritative safety check between the proxy app build and the install: a package already
	 * occupying the real applicationId with a different signing cert was not built by this device's
	 * CoGo, so refuse rather than clobber a third-party install whose data an update cannot
	 * preserve.
	 *
	 * @return the refusal message, or null when the install may proceed.
	 */
	private fun installRefusal(proxyApp: ProxyAppInfo): QuickBuildMessage? {
		val realAppId = proxyApp.proxyAppPackage
		if (packages.uid(realAppId) == null) return null
		val installedCert = packages.signingCertSha256(realAppId)
		val builtCert = apkCertSha256(proxyApp.apk)
		return RealIdInstall
			.signatureRefusal(
				realApplicationId = realAppId,
				realAppInstalled = true,
				installedCertSha256 = installedCert,
				builtCertSha256 = builtCert,
			)?.also {
				log.warn(
					"Refusing to install the Quick Build proxy app over {}: installed cert {} != built cert {}",
					realAppId,
					installedCert,
					builtCert,
				)
			}
	}

	/**
	 * Outcome of one proxy-app-build attempt. [SlotBusy] is split out from [Failed] because the
	 * caller's recovery differs: a proxy app rebuild retry defers (nothing ran, so nothing is owed
	 * a retry charge or an error banner), while a real failure is reported.
	 */
	private sealed interface ProxyAppBuildResult {
		/** A proxy app that built and parsed, with the paths a session needs to work from. */
		data class Ready(
			val proxyApp: ProxyAppInfo,
			val projectRoot: File,
			val moduleDir: File,
			/** The Build Variants selection this build ran, so the session can record it. */
			val variantName: String,
			/** The generation stamped into this build's APK; 0 for an unstamped prebuild. */
			val baselineGeneration: Long,
		) : ProxyAppBuildResult

		/** Another Gradle build owns the single slot, so nothing ran. */
		data object SlotBusy : ProxyAppBuildResult

		/**
		 * [message] replaces the caller's generic wording when the cause is one the user can
		 * act on. Null keeps the generic "proxy app build failed" for genuine build failures.
		 */
		data class Failed(
			val message: String? = null,
		) : ProxyAppBuildResult
	}

	/**
	 * Runs the proxy app build and parses setup.json; logs on every non-[ProxyAppBuildResult.Ready].
	 *
	 * @param purpose why this build runs, which decides whether it stamps a fresh baseline
	 *   generation into the APK - see [ProxyAppBuildPurpose].
	 */
	private suspend fun runProxyAppBuild(purpose: ProxyAppBuildPurpose): ProxyAppBuildResult {
		try {
			QuickBuildArtifactStager.stage(context, paths)

			val projectManager = IProjectManager.getInstance()
			val projectRoot = File(projectManager.projectDirPath)

			// The project model only exists once CoGo's Gradle sync has populated it, and a tap
			// during sync is common (the user opens a project and reaches straight for Quick
			// Build). Queue behind the sync rather than failing: the session is already in
			// Provisioning, so the toolbar has shown the stop glyph and the tap is acknowledged.
			if (!awaitProjectModel { projectManager.workspace != null }) {
				log.error("Project model still unavailable after {} ms; giving up", PROJECT_MODEL_TIMEOUT_MS)
				return ProxyAppBuildResult.Failed(
					context.getString(R.string.quick_build_waiting_for_sync),
				)
			}

			val module =
				quickBuildModule()
					?: run {
						log.error("No Android module found for the Quick Build proxy app build")
						return ProxyAppBuildResult.Failed(
							context.getString(R.string.quick_build_no_app_module),
						)
					}
			val moduleDir = moduleDir(projectRoot, module.path)

			// The variant the Build Variants sidebar shows, exactly as the standard Run button
			// resolves it. The flavor-agnostic `assembleDebug` LIFECYCLE task would build EVERY
			// flavor on a flavored project, leaving CoGo to install whichever flavor's report
			// landed last, under an applicationId the user never selected.
			val variantName = module.getSelectedVariant()?.name ?: QuickBuildTaskPaths.DEFAULT_VARIANT
			QuickBuildProjectSupport.nonDebuggableVariantMessage(variantName)?.let { refusal ->
				log.error("Quick Build needs a debuggable variant; '{}' is selected", variantName)
				return ProxyAppBuildResult.Failed(context.getString(refusal, variantName))
			}

			val buildService =
				Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
					?: run {
						log.error("Build service unavailable for the Quick Build proxy app build")
						return ProxyAppBuildResult.Failed()
					}

			// Allocated (and persisted) before the build runs, from the same counter hot
			// deploys draw from, so the installed baseline is strictly older than every
			// later deploy. A failed build burns the number, which is fine - the counter
			// only has to stay monotonic, not dense.
			val baselineGeneration = if (purpose.stampBaseline) nextBaselineGeneration(projectRoot) else null
			val gradleArgs =
				listOfNotNull(
					"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED}=true",
					"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR}=" +
						paths.runtimeAar.absolutePath,
					baselineGeneration?.let {
						"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_BASELINE_GENERATION}=$it"
					},
				)
			val message =
				TaskExecutionMessage(
					tasks = listOf(QuickBuildTaskPaths.assembleVariant(module.path, variantName)),
					buildId = buildService.nextBuildId(BuildRunType.TaskRun),
					buildParams = GradleBuildParams(gradleArgs = gradleArgs),
				)

			// One Gradle build at a time on the device, checked as late as possible - the
			// staging and project-model work above takes seconds, and CoGo's own project sync
			// fires on exactly the gradle-file change that invalidates a Quick Build session,
			// so the two race here regularly. Reading the same raw in-progress flag CoGo's own
			// build guards read keeps this a distinguishable outcome instead of an
			// "IllegalStateException: Build is already in progress" that reads as a build failure.
			if (buildService.isBuildInProgress) {
				log.info("A Gradle build is already in progress; not starting the Quick Build proxy app build")
				return ProxyAppBuildResult.SlotBusy
			}

			// The proxy app build goes through the SAME executeTasks path as the user's Standard
			// Run, and GradleBuildService has ONE editor event listener - so without this bracket
			// the prebuild drives the EDITOR's build UI on every project open: the modal
			// first-build notice (consuming the isFirstBuild flag the REAL first build should
			// get), the output sheet, and a Run button relabelled to "Cancel build" whose tap
			// cancels Quick Build's own provisioning.
			val gradleService = buildService as? GradleBuildService
			// The bracket keeps the editor's build UI out of the way, not the output: report the
			// tasks as they run, so a ~90 s provision reads as progress rather than a hang.
			val progressListener = narrator?.let { { line: String -> it.narrateProxyAppProgress(line) } }
			// The bracket spans the AWAIT, not just the executeTasks call: executeTasks hands
			// back a future immediately and every listener callback arrives while it is
			// pending, so releasing earlier would un-suppress the ones that matter most.
			val runBuild: suspend () -> TaskExecutionResult = {
				withContext(Dispatchers.IO) { buildService.executeTasks(message) }.await()
			}
			val result =
				if (gradleService != null) {
					gradleService.withInternalBuild(progressListener, runBuild)
				} else {
					// No bracket to take, so nothing to suppress; the build still runs.
					runBuild()
				}
			if (result == null || !result.isSuccessful) {
				log.error("Quick-build proxy app build failed: {}", result?.failure)
				// The bracket above suppressed the editor's build listener, and result.failure is
				// a bare enum, so the captured output is the ONLY place Gradle's reason exists.
				// Narrate it into Build Output or the user is told a build failed and never why.
				val captured = gradleService?.takeInternalBuildOutput().orEmpty()
				narrator?.narrateProxyAppBuildFailure(captured)
				return ProxyAppBuildResult.Failed(quickBuildProxyAppFailureSummary(captured))
			}

			// Variant-scoped, matching where the Gradle plugin writes it: one report per
			// debuggable variant, so a flavored project has several and only this variant's is
			// the built app.
			val reportPath = QuickBuildTaskPaths.setupJson(variantName)
			val reportFile =
				sequenceOf(
					File(moduleDir, reportPath),
					File(projectRoot, reportPath),
				).firstOrNull { it.isFile }
					?: run {
						log.error(
							"{} not found under {} or {} after the proxy app build",
							reportPath,
							moduleDir,
							projectRoot,
						)
						// The build succeeded but wrote no Quick Build setup, which all but
						// names the cause: the plugin only configures DEBUGGABLE variants, and
						// the release-name check above only catches AGP's own release build
						// type. Say so instead of the generic "setup failed".
						return ProxyAppBuildResult.Failed(
							context.getString(R.string.quick_build_variant_setup_missing, variantName),
						)
					}

			val proxyApp =
				ProxyAppInfo.parse(reportFile.readText(), projectRoot)
					?: run {
						log.error("Unparseable setup.json at {}", reportFile)
						return ProxyAppBuildResult.Failed()
					}

			return ProxyAppBuildResult.Ready(
				proxyApp,
				projectRoot,
				moduleDir,
				variantName,
				baselineGeneration ?: 0L,
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.error("Quick-build proxy app build failed", e)
			return ProxyAppBuildResult.Failed()
		}
	}

	/**
	 * Hands a cancellation to the Gradle build currently running through the tooling server.
	 *
	 * The device has a single cancellation token, so this refuses unless the in-flight build
	 * is an INTERNAL one (Quick Build provision/prebuild/proxy app rebuild). The caller only ever issues
	 * this while the session owns the slot, but the check is enforced here rather than left to
	 * the caller: a comment cannot stop a stop-tap from killing the user's own Standard Run.
	 */
	override fun cancelProxyAppBuild(): Boolean {
		val buildService =
			Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
				?: return false
		if (!buildService.isBuildInProgress) return false
		if (buildService.isUserVisibleBuildInProgress) {
			log.warn("Refusing to cancel: the in-flight Gradle build is the user's, not Quick Build's")
			return false
		}
		return try {
			buildService.cancelCurrentBuild()
			true
		} catch (e: Throwable) {
			// A tooling server that is gone cannot be asked to cancel; the caller falls back
			// to tearing the session down, so this is not worth surfacing.
			log.warn("Could not cancel the Quick Build proxy app build", e)
			false
		}
	}

	/** `:app` -> `<root>/app`; nested paths (`:feature:home`) map to nested dirs. */
	private fun moduleDir(
		projectRoot: File,
		gradlePath: String,
	): File =
		if (gradlePath == ":" || gradlePath.isBlank()) {
			projectRoot
		} else {
			File(projectRoot, gradlePath.trim(':').replace(':', File.separatorChar))
		}

	companion object {
		private val log = LoggerFactory.getLogger("QB-Provisioner")

		/**
		 * The module Quick Build provisions: the first Android application module, and failing
		 * that the first Android module at all. The same choice the proxy app build makes, so
		 * a variant read through here names the variant that was actually built.
		 */
		private fun quickBuildModule(): AndroidModule? =
			IProjectManager.getInstance().let { manager ->
				manager.getAndroidAppModules().firstOrNull()
					?: manager.getAndroidModules().firstOrNull()
			}

		/**
		 * The Build Variants selection Quick Build would build right now, or null when the
		 * project model has no module to ask - during a sync, or for a project with no Android
		 * module. Null is not "changed": a session's variant check treats an unknown selection
		 * as no evidence of a switch, so a mid-sync read cannot tear a healthy session down.
		 */
		fun selectedVariantName(): String? = quickBuildModule()?.getSelectedVariant()?.name

		/**
		 * How long a tap waits for CoGo's Gradle sync to publish the project model before giving
		 * up. Generous on purpose: a cold sync on a low-spec device is minutes, and failing the tap
		 * instead makes an ordinary "opened the project and tapped" read as a build failure.
		 */
		const val PROJECT_MODEL_TIMEOUT_MS = 180_000L

		private const val PROJECT_MODEL_POLL_MS = 250L

		/**
		 * Suspends until [isReady] returns true, or [timeoutMs] elapses. Returns whether it
		 * became ready. [IProjectManager.workspace] is a plain field with no change signal,
		 * so this polls rather than observes; [sleep] is injected so tests drive it on
		 * virtual time instead of real delays.
		 */
		suspend fun awaitProjectModel(
			timeoutMs: Long = PROJECT_MODEL_TIMEOUT_MS,
			pollMs: Long = PROJECT_MODEL_POLL_MS,
			sleep: suspend (Long) -> Unit = { delay(it) },
			isReady: () -> Boolean,
		): Boolean {
			if (isReady()) {
				return true
			}
			log.info("Project model not ready; waiting up to {} ms for the sync to finish", timeoutMs)
			var waited = 0L
			while (waited < timeoutMs) {
				sleep(pollMs)
				waited += pollMs
				if (isReady()) {
					log.info("Project model became available after {} ms", waited)
					return true
				}
			}
			return false
		}

		/**
		 * A [ProvisionOutcome.Failure] sends the session back to Idle, where returning to
		 * CoGo is a no-op (there is no parked session for HostForegrounded to auto-retry) -
		 * so the installer's DIALOG_NOT_SHOWN "return to CoGo to confirm" guidance is a
		 * dead end on THIS path, unlike the proxy app rebuild park where it is exactly right.
		 * Swap in tap guidance; DECLINED and TIMED_OUT already carry their own.
		 */
		@StringRes
		fun initialProvisionMessageOverride(outcome: InstallOutcome.ConfirmationNotGiven): Int? =
			if (outcome.reason == InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN) {
				R.string.quick_build_reinstall_tap_again
			} else {
				// The installer already names the tap remedy for DECLINED and TIMED_OUT, so
				// there is nothing to override - its own message stands.
				null
			}
	}
}
