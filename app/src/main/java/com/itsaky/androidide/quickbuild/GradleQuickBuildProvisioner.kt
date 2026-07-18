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
import org.appdevforall.cotg.quickbuild.service.InstallOutcome
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
 */
class GradleQuickBuildProvisioner(
	private val context: Context,
	private val paths: EnvironmentQuickBuildPaths,
	private val installer: TestAppInstaller,
) : QuickBuildProvisioner {
	override suspend fun provision(): ProvisionOutcome {
		val setupResult =
			runSetupBuild() ?: return ProvisionOutcome.Failure("Quick Build setup build failed")
		val (setup, projectRoot, moduleDir) = setupResult

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
				),
		)
	}

	override suspend fun warmSetupBuild() {
		// Eager B2 warm-up: run the setup build, install nothing. The tap-time
		// provision() re-runs it against current disk (fast: tasks up-to-date), so a
		// stale warm result can never become the session baseline.
		if (runSetupBuild() == null) {
			log.warn("Eager quick-build setup build did not complete; the first tap retries")
		}
	}

	override suspend fun rebaseline(): RebaselineOutcome {
		val setupResult =
			runSetupBuild() ?: return RebaselineOutcome.Failure("Re-baseline build failed")
		// The installer skips when the rebuilt APK is byte-identical to what is
		// installed (common when a gradle edit did not change the test app), so a
		// rebaseline only re-prompts the user when the APK really changed.
		return when (
			val installed =
				installer.ensureInstalled(setupResult.setup.apk, setupResult.setup.testAppPackage)
		) {
			is InstallOutcome.Failed -> RebaselineOutcome.Failure(installed.message)
			is InstallOutcome.Installed -> RebaselineOutcome.Success
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

			val message =
				TaskExecutionMessage(
					tasks = listOf("${module.path}:assembleDebug"),
					buildId = buildService.nextBuildId(BuildRunType.TaskRun),
					buildParams =
						GradleBuildParams(
							gradleArgs =
								listOf(
									"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED}=true",
									"-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR}=" +
										paths.runtimeAar.absolutePath,
								),
						),
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
