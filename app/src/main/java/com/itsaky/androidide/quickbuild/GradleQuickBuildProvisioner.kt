package com.itsaky.androidide.quickbuild

import android.content.Context
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.GradlePluginConfig
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.GradleBuildParams
import com.itsaky.androidide.tooling.api.messages.TaskExecutionMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.SetupInfo
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SameAppIdGuard
import org.appdevforall.cotg.quickbuild.domain.SameAppIdProvisionDecision
import org.appdevforall.cotg.quickbuild.domain.SameAppIdProvisionGuard
import org.appdevforall.cotg.quickbuild.domain.SameAppIdRefusalReason
import org.appdevforall.cotg.quickbuild.service.InstallOutcome
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.QuickBuildModeStore
import org.appdevforall.cotg.quickbuild.service.QuickBuildProvisioner
import org.appdevforall.cotg.quickbuild.service.RebaselineOutcome
import org.appdevforall.cotg.quickbuild.service.TestAppInstaller
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Real-Gradle side of quick-build provisioning (plan 2.2): stages the bundled
 * artifacts, runs the setup build through the existing [BuildService.executeTasks]
 * path with the quick-build `-P` properties (LogSender-AAR pattern), reads the setup
 * manifest the Gradle plugin writes, and hands the built test app to [installer] -
 * which reuses CoGo's Run install pathway (plan B1) and skips the install entirely
 * when the device already runs those exact APK bytes.
 *
 * Same-app-id mode (Path B, `quick-build/docs/same-app-id-design.md`): when the
 * per-project toggle is on and the episode confirmed, the setup build additionally
 * gets `-Pcotg.quickbuild.sameAppId=true` and the episode's pinned
 * `-Pcotg.quickbuild.versionCodeOverride`. Before any install the provisioner runs the
 * [SameAppIdGuard] assertions and the authoritative signature check (built APK cert vs
 * installed real app cert) - a mismatch refuses loud, never uninstalls.
 */
class GradleQuickBuildProvisioner(
	private val context: Context,
	private val paths: EnvironmentQuickBuildPaths,
	private val installer: TestAppInstaller,
	private val packages: InstalledPackages,
	private val modeStore: QuickBuildModeStore,
	private val guard: SameAppIdGuard,
	private val metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop,
	/** SHA-256 of an APK file's signing cert; app wiring uses PackageManager. */
	private val apkCertSha256: (File) -> String? = { null },
) : QuickBuildProvisioner {
	/** Snapshot of the persisted mode for ONE build, so toggle races cannot split it. */
	private data class ModeSnapshot(
		val sameAppId: Boolean,
		val pinnedVersionCode: Int?,
	)

	override suspend fun provision(): ProvisionOutcome {
		val mode = modeSnapshot()
		preflightFailure(mode)?.let { return ProvisionOutcome.Failure(it) }

		val setupResult =
			runSetupBuild(mode) ?: return ProvisionOutcome.Failure("Quick Build setup build failed")
		val (setup, projectRoot, moduleDir) = setupResult

		installRefusal(mode, setup)?.let { return ProvisionOutcome.Failure(it) }

		val uid =
			when (val installed = installer.ensureInstalled(setup.apk, setup.testAppPackage)) {
				is InstallOutcome.Failed -> return ProvisionOutcome.Failure(installed.message)
				is InstallOutcome.Installed -> installed.uid
			}

		return ProvisionOutcome.Success(
			setup = setup,
			testAppUid = uid,
			layout =
				DefaultQuickBuildProjectLayout(
					projectRoot = projectRoot,
					appModuleDir = moduleDir,
					classpath = setup.classpath,
					extraSourceRoots = setup.sourceRoots,
				),
		)
	}

	override suspend fun warmSetupBuild() {
		// Eager B2 warm-up: run the setup build, install nothing - so no clobber can
		// happen before the user confirms (contract section 1). The tap-time
		// provision() re-runs it against current disk (fast: tasks up-to-date), so a
		// stale warm result can never become the session baseline.
		val mode = modeSnapshot()
		if (mode.sameAppId && preflightFailure(mode) != null) {
			log.warn("Same-app-id episode not confirmed; skipping the warm setup build")
			return
		}
		if (runSetupBuild(mode) == null) {
			log.warn("Eager quick-build setup build did not complete; the first tap retries")
		}
	}

	override suspend fun rebaseline(): RebaselineOutcome {
		val mode = modeSnapshot()
		preflightFailure(mode)?.let { return RebaselineOutcome.Failure(it) }

		val setupResult =
			runSetupBuild(mode) ?: return RebaselineOutcome.Failure("Re-baseline build failed")

		installRefusal(mode, setupResult.setup)?.let { return RebaselineOutcome.Failure(it) }

		// The installer skips when the rebuilt APK is byte-identical to what is
		// installed (common when a gradle edit did not change the test app), so a
		// rebaseline only re-prompts the user when the APK really changed.
		return when (
			val installed =
				installer.ensureInstalled(setupResult.setup.apk, setupResult.setup.testAppPackage)
		) {
			is InstallOutcome.Failed -> RebaselineOutcome.Failure(installed.message)
			is InstallOutcome.Installed ->
				RebaselineOutcome.Success(
					setup = setupResult.setup,
					layout =
						DefaultQuickBuildProjectLayout(
							projectRoot = setupResult.projectRoot,
							appModuleDir = setupResult.moduleDir,
							classpath = setupResult.setup.classpath,
							extraSourceRoots = setupResult.setup.sourceRoots,
						),
				)
		}
	}

	private fun modeSnapshot(): ModeSnapshot =
		ModeSnapshot(
			sameAppId = modeStore.isSameAppIdEnabled(),
			pinnedVersionCode = modeStore.pinnedVersionCode(),
		)

	/**
	 * Same-app-id checks that must pass BEFORE the setup build (contract section 2).
	 * Pure decision in [SameAppIdProvisionGuard.preflight]; null = proceed.
	 */
	private fun preflightFailure(mode: ModeSnapshot): String? =
		SameAppIdProvisionGuard.preflight(mode.sameAppId, mode.pinnedVersionCode, guard)

	/**
	 * Same-app-id checks between the setup build and the install (contract sections 2 +
	 * 7). This method does only the I/O - reading the installed and built signing certs,
	 * emitting refusal analytics, logging; the decision itself lives in the JVM-tested
	 * [SameAppIdProvisionGuard.installGate]. Null = install may proceed.
	 */
	private fun installRefusal(
		mode: ModeSnapshot,
		setup: SetupInfo,
	): String? {
		val realAppId =
			if (setup.sameAppId) {
				setup.testAppPackage
			} else {
				setup.testAppPackage.removeSuffix(SameAppIdGuard.TEST_APP_ID_SUFFIX)
			}
		// Certs are read only on the same-app-id update path, where the real app exists.
		val realAppInstalled = mode.sameAppId && packages.uid(realAppId) != null
		val installedCert = if (realAppInstalled) packages.signingCertSha256(realAppId) else null
		val builtCert = if (realAppInstalled) apkCertSha256(setup.apk) else null

		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = mode.sameAppId,
				pinnedVersionCode = mode.pinnedVersionCode,
				setupSameAppId = setup.sameAppId,
				setupVersionCode = setup.versionCode,
				testAppPackage = setup.testAppPackage,
				realApplicationId = realAppId,
				realAppInstalled = realAppInstalled,
				installedCertSha256 = installedCert,
				builtCertSha256 = builtCert,
				guard = guard,
			)
		return when (decision) {
			is SameAppIdProvisionDecision.Proceed -> null
			is SameAppIdProvisionDecision.Refuse -> {
				if (decision.signatureMismatch) {
					log.warn(
						"Same-app-id signature mismatch for {}: installed={}, built={}",
						realAppId,
						installedCert,
						builtCert,
					)
					try {
						metrics.onSameAppIdRefused(SameAppIdRefusalReason.SIGNATURE_MISMATCH)
					} catch (e: Throwable) {
						log.warn("Quick Build metrics sink failed", e)
					}
				}
				decision.message
			}
		}
	}

	private data class SetupResult(
		val setup: SetupInfo,
		val projectRoot: File,
		val moduleDir: File,
	)

	/** Runs the setup build and parses setup.json; null (with a log) on any failure. */
	private suspend fun runSetupBuild(mode: ModeSnapshot): SetupResult? {
		try {
			QuickBuildArtifactStager.stage(context, paths)

			val projectManager = IProjectManager.getInstance()
			val projectRoot = File(projectManager.projectDirPath)
			val module =
				projectManager.getAndroidAppModules().firstOrNull()
					?: projectManager.getAndroidModules().firstOrNull()
					?: run {
						log.error("No Android module found for quick-build setup")
						return null
					}
			val moduleDir = moduleDir(projectRoot, module.path)

			val buildService =
				Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
					?: run {
						log.error("Build service unavailable for quick-build setup")
						return null
					}

			val gradleArgs =
				buildList {
					add("-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED}=true")
					add(
						"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR}=" +
							paths.runtimeAar.absolutePath,
					)
					if (mode.sameAppId) {
						add("-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_SAME_APP_ID}=true")
						mode.pinnedVersionCode?.let {
							add("-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_VERSION_CODE_OVERRIDE}=$it")
						}
					}
				}
			val message =
				TaskExecutionMessage(
					tasks = listOf("${module.path}:assembleDebug"),
					buildId = buildService.nextBuildId(BuildRunType.TaskRun),
					buildParams = GradleBuildParams(gradleArgs = gradleArgs),
				)

			val result = withContext(Dispatchers.IO) { buildService.executeTasks(message) }.await()
			if (result == null || !result.isSuccessful) {
				log.error("Quick-build setup build failed: {}", result?.failure)
				return null
			}

			val setupJson =
				sequenceOf(
					File(moduleDir, "build/quickbuild/setup.json"),
					File(projectRoot, "build/quickbuild/setup.json"),
				).firstOrNull { it.isFile }
					?: run {
						log.error(
							"setup.json not found under {} or {} after the setup build",
							moduleDir,
							projectRoot,
						)
						return null
					}

			val setup =
				SetupInfo.parse(setupJson.readText(), projectRoot)
					?: run {
						log.error("Unparseable setup.json at {}", setupJson)
						return null
					}

			return SetupResult(setup, projectRoot, moduleDir)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			log.error("Quick-build setup build failed", e)
			return null
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
	}
}
