package com.itsaky.androidide.quickbuild

import android.content.Context
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.tooling.api.GradlePluginConfig
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.domain.RealIdInstall
import org.appdevforall.cotg.quickbuild.service.InstallOutcome
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.QuickBuildProvisioner
import org.appdevforall.cotg.quickbuild.service.RebaselineOutcome
import org.appdevforall.cotg.quickbuild.service.ProxyAppInstaller
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Real-Gradle side of quick-build provisioning (plan 2.2): stages the bundled
 * artifacts, runs the proxy app build through the existing [BuildService.executeTasks]
 * path with the quick-build `-P` properties (LogSender-AAR pattern), reads the proxy app
 * report the Gradle plugin writes, and hands the built proxy app to [installer] -
 * which reuses CoGo's Run install pathway (plan B1) and skips the install entirely
 * when the device already runs those exact APK bytes.
 *
 * The proxy app installs under the project's real applicationId (Quick Build and Standard
 * Run share the one package slot). Before installing over an existing real-id package the
 * provisioner runs an authoritative signature check (built APK cert vs installed cert): a
 * mismatch means a third-party install of the same id is occupying the slot, so it refuses
 * loud and never uninstalls (the user must remove it manually). The switch confirmation
 * between build types is handled CoGo-side, before provisioning starts.
 */
class GradleQuickBuildProvisioner(
	private val context: Context,
	private val paths: EnvironmentQuickBuildPaths,
	private val installer: ProxyAppInstaller,
	private val packages: InstalledPackages,
	/** SHA-256 of an APK file's signing cert; app wiring uses PackageManager. */
	private val apkCertSha256: (File) -> String? = { null },
) : QuickBuildProvisioner {
	override suspend fun provision(): ProvisionOutcome {
		unsupportedProjectTypeFailure()?.let { return ProvisionOutcome.Failure(it) }

		// A busy Gradle slot folds into the same failure as any other: from Idle the next tap
		// re-provisions, so there is no parked state to defer into (unlike [rebaseline]).
		val buildResult =
			runProxyAppBuild() as? ProxyAppBuildResult.Ready
				?: return ProvisionOutcome.Failure("Quick Build proxy app build failed")
		val (proxyApp, projectRoot, moduleDir) = buildResult

		QuickBuildProjectSupport
			.noLaunchableActivityMessage(proxyApp.entryActivity)
			?.let { return ProvisionOutcome.Failure(it) }
		installRefusal(proxyApp)?.let { return ProvisionOutcome.Failure(it) }

		val uid =
			when (val installed = installer.ensureInstalled(proxyApp.apk, proxyApp.proxyAppPackage)) {
				is InstallOutcome.Failed -> {
					return ProvisionOutcome.Failure(installed.message)
				}

				// From Idle the next tap re-provisions (fast: tasks up-to-date), so the
				// existing failure surface already IS the retry offer here.
				is InstallOutcome.ConfirmationNotGiven -> {
					return ProvisionOutcome.Failure(initialProvisionMessage(installed))
				}

				is InstallOutcome.Installed -> {
					installed.uid
				}
			}

		return ProvisionOutcome.Success(
			proxyApp = proxyApp,
			proxyAppUid = uid,
			layout =
				DefaultQuickBuildProjectLayout(
					projectRoot = projectRoot,
					appModuleDir = moduleDir,
					classpath = proxyApp.classpath,
					extraSourceRoots = proxyApp.sourceRoots,
					stableIdsFile = proxyApp.stableIdsFile,
					libraryResourceFlats = proxyApp.libraryResourceFlats,
				),
		)
	}

	override suspend fun prebuildProxyApp() {
		// Eager B2 warm-up: run the proxy app build, install nothing - so no clobber can
		// happen before the user confirms (contract section 1). The tap-time
		// provision() re-runs it against current disk (fast: tasks up-to-date), so a
		// stale warm result can never become the session baseline.
		if (unsupportedProjectTypeFailure() != null) {
			log.warn("Quick Build unsupported for this project type; skipping the proxy app prebuild")
			return
		}
		if (runProxyAppBuild() !is ProxyAppBuildResult.Ready) {
			log.warn("Eager quick-build proxy app build did not complete; the first tap retries")
		}
	}

	override suspend fun rebaseline(): RebaselineOutcome {
		unsupportedProjectTypeFailure()?.let { return RebaselineOutcome.Failure(it) }

		val buildResult =
			when (val built = runProxyAppBuild()) {
				is ProxyAppBuildResult.Ready -> built

				// Nothing ran, so this is not a build failure: the session parks back and
				// retries later WITHOUT spending its bounded auto-retry budget.
				ProxyAppBuildResult.SlotBusy -> return RebaselineOutcome.BuildSlotBusy

				ProxyAppBuildResult.Failed -> return RebaselineOutcome.Failure("Re-baseline build failed")
			}

		QuickBuildProjectSupport
			.noLaunchableActivityMessage(buildResult.proxyApp.entryActivity)
			?.let { return RebaselineOutcome.Failure(it) }
		installRefusal(buildResult.proxyApp)?.let { return RebaselineOutcome.Failure(it) }

		// The installer skips when the rebuilt APK is byte-identical to what is
		// installed (common when a gradle edit did not change the proxy app), so a
		// rebaseline only re-prompts the user when the APK really changed.
		return when (
			val installed =
				installer.ensureInstalled(buildResult.proxyApp.apk, buildResult.proxyApp.proxyAppPackage)
		) {
			is InstallOutcome.Failed -> {
				RebaselineOutcome.Failure(installed.message)
			}

			is InstallOutcome.ConfirmationNotGiven -> {
				// The rebuilt APK is good; only the user's confirmation is missing (no
				// dialog shown / cancelled / left untapped - the message says which).
				// Keep that distinguishable so the session can offer a retry instead of
				// dying to Idle (the stranded-session failure the multi-module verify
				// found).
				RebaselineOutcome.InstallNotConfirmed(installed.message)
			}

			is InstallOutcome.Installed -> {
				RebaselineOutcome.Success(
					proxyApp = buildResult.proxyApp,
					layout =
						DefaultQuickBuildProjectLayout(
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
	private fun unsupportedProjectTypeFailure(): String? =
		QuickBuildProjectSupport.unsupportedProjectTypeMessage(
			IProjectManager.getInstance().isPluginProject(),
		)

	/**
	 * The authoritative safety check between the proxy app build and the install: if a package
	 * already occupies the real applicationId and its signing cert differs from the freshly
	 * built proxy app APK's, it was not built by this device's CoGo - refuse loud rather than
	 * clobber a third-party install whose data an update cannot preserve. No installed app,
	 * or a matching cert (CoGo's own Quick Build or Standard Run build), proceeds. Null =
	 * install may proceed.
	 */
	private fun installRefusal(proxyApp: ProxyAppInfo): String? {
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
	 * caller's recovery differs: a rebaseline retry defers (nothing ran, so nothing is owed
	 * a retry charge or an error banner), while a real failure is reported.
	 */
	private sealed interface ProxyAppBuildResult {
		data class Ready(
			val proxyApp: ProxyAppInfo,
			val projectRoot: File,
			val moduleDir: File,
		) : ProxyAppBuildResult

		data object SlotBusy : ProxyAppBuildResult

		data object Failed : ProxyAppBuildResult
	}

	/** Runs the proxy app build and parses setup.json; logs on every non-[ProxyAppBuildResult.Ready]. */
	private suspend fun runProxyAppBuild(): ProxyAppBuildResult {
		try {
			QuickBuildArtifactStager.stage(context, paths)

			val projectManager = IProjectManager.getInstance()
			val projectRoot = File(projectManager.projectDirPath)
			val module =
				projectManager.getAndroidAppModules().firstOrNull()
					?: projectManager.getAndroidModules().firstOrNull()
					?: run {
						log.error("No Android module found for the Quick Build proxy app build")
						return ProxyAppBuildResult.Failed
					}
			val moduleDir = moduleDir(projectRoot, module.path)

			val buildService =
				Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
					?: run {
						log.error("Build service unavailable for the Quick Build proxy app build")
						return ProxyAppBuildResult.Failed
					}

			val gradleArgs =
				listOf(
					"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED}=true",
					"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR}=" +
						paths.runtimeAar.absolutePath,
				)
			val message =
				TaskExecutionMessage(
					tasks = listOf(QuickBuildTaskPaths.assembleDebug(module.path)),
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
			// Run, and GradleBuildService has ONE editor event listener - so without this
			// bracket the prewarm drives the EDITOR's build UI on every project open: status
			// line, the modal first-build notice (consuming the isFirstBuild flag the REAL
			// first build should get), the build-output sheet, and the Run button relabelled
			// to "Cancel build" for the whole window, where a tap cancels Quick Build's own
			// provisioning. Bracketed here rather than re-gating prewarm: warming up whenever
			// the feature is on is the point.
			val gradleService = buildService as? GradleBuildService
			gradleService?.beginInternalBuild()
			// Ends only after the AWAIT, not after executeTasks returns: executeTasks hands
			// back a future immediately and every listener callback arrives while it is
			// pending, so releasing earlier would un-suppress the ones that matter most.
			val result =
				try {
					withContext(Dispatchers.IO) { buildService.executeTasks(message) }.await()
				} finally {
					gradleService?.endInternalBuild()
				}
			if (result == null || !result.isSuccessful) {
				log.error("Quick-build proxy app build failed: {}", result?.failure)
				return ProxyAppBuildResult.Failed
			}

			val reportFile =
				sequenceOf(
					File(moduleDir, "build/quickbuild/setup.json"),
					File(projectRoot, "build/quickbuild/setup.json"),
				).firstOrNull { it.isFile }
					?: run {
						log.error(
							"setup.json not found under {} or {} after the proxy app build",
							moduleDir,
							projectRoot,
						)
						return ProxyAppBuildResult.Failed
					}

			val proxyApp =
				ProxyAppInfo.parse(reportFile.readText(), projectRoot)
					?: run {
						log.error("Unparseable setup.json at {}", reportFile)
						return ProxyAppBuildResult.Failed
					}

			return ProxyAppBuildResult.Ready(proxyApp, projectRoot, moduleDir)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.error("Quick-build proxy app build failed", e)
			return ProxyAppBuildResult.Failed
		}
	}

	/**
	 * Hands a cancellation to the Gradle build currently running through the tooling server.
	 *
	 * The device has a single cancellation token, so this refuses unless the in-flight build
	 * is an INTERNAL one (Quick Build provision/prewarm/rebaseline). The caller only ever issues
	 * this while the session owns the slot, but that invariant used to live in a comment - and
	 * a comment cannot stop a stop-tap from killing the user's own Standard Run.
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
		private val log = LoggerFactory.getLogger(GradleQuickBuildProvisioner::class.java)

		/**
		 * A [ProvisionOutcome.Failure] sends the session back to Idle, where returning to
		 * CoGo is a no-op (there is no parked session for HostForegrounded to auto-retry) -
		 * so the installer's DIALOG_NOT_SHOWN "return to CoGo to confirm" guidance is a
		 * dead end on THIS path, unlike the rebaseline park where it is exactly right.
		 * Swap in tap guidance; DECLINED and TIMED_OUT already carry their own.
		 */
		fun initialProvisionMessage(outcome: InstallOutcome.ConfirmationNotGiven): String =
			if (outcome.reason == InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN) {
				"Your app needs a reinstall - return to CoGo and tap Quick Build to try again."
			} else {
				outcome.message
			}
	}
}
