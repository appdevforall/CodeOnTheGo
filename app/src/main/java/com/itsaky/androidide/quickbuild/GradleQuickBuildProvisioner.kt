package com.itsaky.androidide.quickbuild

import android.content.Context
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.isPluginProject
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
import org.appdevforall.cotg.quickbuild.domain.RealIdInstall
import org.appdevforall.cotg.quickbuild.service.InstallOutcome
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.ProvisionOutcome
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
 * The test app installs under the project's real applicationId (Quick Build and Standard
 * Run share the one package slot). Before installing over an existing real-id package the
 * provisioner runs an authoritative signature check (built APK cert vs installed cert): a
 * mismatch means a third-party install of the same id is occupying the slot, so it refuses
 * loud and never uninstalls (the user must remove it manually). The switch confirmation
 * between build types is handled CoGo-side, before provisioning starts.
 */
class GradleQuickBuildProvisioner(
	private val context: Context,
	private val paths: EnvironmentQuickBuildPaths,
	private val installer: TestAppInstaller,
	private val packages: InstalledPackages,
	/** SHA-256 of an APK file's signing cert; app wiring uses PackageManager. */
	private val apkCertSha256: (File) -> String? = { null },
) : QuickBuildProvisioner {
	override suspend fun provision(): ProvisionOutcome {
		unsupportedProjectTypeFailure()?.let { return ProvisionOutcome.Failure(it) }

		val setupResult =
			runSetupBuild() ?: return ProvisionOutcome.Failure("Quick Build setup build failed")
		val (setup, projectRoot, moduleDir) = setupResult

		QuickBuildProjectSupport
			.noLaunchableActivityMessage(setup.entryActivity)
			?.let { return ProvisionOutcome.Failure(it) }
		installRefusal(setup)?.let { return ProvisionOutcome.Failure(it) }

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
					stableIdsFile = setup.stableIdsFile,
					libraryResourceFlats = setup.libraryResourceFlats,
				),
		)
	}

	override suspend fun warmSetupBuild() {
		// Eager B2 warm-up: run the setup build, install nothing - so no clobber can
		// happen before the user confirms (contract section 1). The tap-time
		// provision() re-runs it against current disk (fast: tasks up-to-date), so a
		// stale warm result can never become the session baseline.
		if (unsupportedProjectTypeFailure() != null) {
			log.warn("Quick Build unsupported for this project type; skipping the warm setup build")
			return
		}
		if (runSetupBuild() == null) {
			log.warn("Eager quick-build setup build did not complete; the first tap retries")
		}
	}

	override suspend fun rebaseline(): RebaselineOutcome {
		unsupportedProjectTypeFailure()?.let { return RebaselineOutcome.Failure(it) }

		val setupResult =
			runSetupBuild() ?: return RebaselineOutcome.Failure("Re-baseline build failed")

		QuickBuildProjectSupport
			.noLaunchableActivityMessage(setupResult.setup.entryActivity)
			?.let { return RebaselineOutcome.Failure(it) }
		installRefusal(setupResult.setup)?.let { return RebaselineOutcome.Failure(it) }

		// The installer skips when the rebuilt APK is byte-identical to what is
		// installed (common when a gradle edit did not change the test app), so a
		// rebaseline only re-prompts the user when the APK really changed.
		return when (
			val installed =
				installer.ensureInstalled(setupResult.setup.apk, setupResult.setup.testAppPackage)
		) {
			is InstallOutcome.Failed -> {
				RebaselineOutcome.Failure(installed.message)
			}

			is InstallOutcome.Installed -> {
				RebaselineOutcome.Success(
					setup = setupResult.setup,
					layout =
						DefaultQuickBuildProjectLayout(
							projectRoot = setupResult.projectRoot,
							appModuleDir = setupResult.moduleDir,
							classpath = setupResult.setup.classpath,
							extraSourceRoots = setupResult.setup.sourceRoots,
							stableIdsFile = setupResult.setup.stableIdsFile,
							libraryResourceFlats = setupResult.setup.libraryResourceFlats,
						),
				)
			}
		}
	}

	/**
	 * Quick Build can't provision a plugin project (its artifact is a `.cgp`, not a
	 * runnable app) - checked up front so this fails fast with a friendly message
	 * instead of a raw Gradle `TaskSelectionException` from the setup build.
	 */
	private fun unsupportedProjectTypeFailure(): String? =
		QuickBuildProjectSupport.unsupportedProjectTypeMessage(
			IProjectManager.getInstance().isPluginProject(),
		)

	/**
	 * The authoritative safety check between the setup build and the install: if a package
	 * already occupies the real applicationId and its signing cert differs from the freshly
	 * built test APK's, it was not built by this device's CoGo - refuse loud rather than
	 * clobber a third-party install whose data an update cannot preserve. No installed app,
	 * or a matching cert (CoGo's own Quick Build or Standard Run build), proceeds. Null =
	 * install may proceed.
	 */
	private fun installRefusal(setup: SetupInfo): String? {
		val realAppId = setup.testAppPackage
		if (packages.uid(realAppId) == null) return null
		val installedCert = packages.signingCertSha256(realAppId)
		val builtCert = apkCertSha256(setup.apk)
		return RealIdInstall
			.signatureRefusal(
				realApplicationId = realAppId,
				realAppInstalled = true,
				installedCertSha256 = installedCert,
				builtCertSha256 = builtCert,
			)?.also {
				log.warn(
					"Refusing to install the Quick Build test app over {}: installed cert {} != built cert {}",
					realAppId,
					installedCert,
					builtCert,
				)
			}
	}

	private data class SetupResult(
		val setup: SetupInfo,
		val projectRoot: File,
		val moduleDir: File,
	)

	/** Runs the setup build and parses setup.json; null (with a log) on any failure. */
	private suspend fun runSetupBuild(): SetupResult? {
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
