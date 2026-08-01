package com.itsaky.androidide

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.helper.ensureOnHomeScreenBeforeCreateProject
import com.itsaky.androidide.helper.isExperimentsFlagSet
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.helper.setAccessibilityEditText
import com.itsaky.androidide.helper.setExperimentsFlagForTest
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.quickbuild.AndroidInstalledPackages
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectKotlinLanguage
import com.itsaky.androidide.screens.ProjectSettingsScreen.setProjectName
import com.itsaky.androidide.screens.QuickBuildScreen.assertQuickBuildButtonShown
import com.itsaky.androidide.screens.QuickBuildScreen.dismissFirstBuildNoticeIfShown
import com.itsaky.androidide.screens.QuickBuildScreen.tapQuickBuildButton
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.cotg.quickbuild.domain.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.QuickBuildClobberCheck
import org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

private const val EDITOR_OPEN_TIMEOUT_MS = 60_000L
private const val PACKAGE_FIELD_TIMEOUT_MS = 3_000L

// Project sync is a real Gradle sync; the same cold-CI ceiling QuickBuildSmokeTest and
// InitializationProjectAndCancelingBuildScenario use.
private const val PROJECT_SYNC_TIMEOUT_MS = 15 * 60 * 1000L
private const val PROJECT_SYNC_POLL_MS = 1_000L

// Provisioning (proxy app build + install + daemon spawn) is ALSO a real Gradle build on
// the device's single build slot, so it gets the same cold-build ceiling as project sync.
private const val PROVISIONING_READY_TIMEOUT_MS = 15 * 60 * 1000L

// A live-reload build+deploy is the fast incremental path (measured 12-50s warm-daemon in
// prior on-device runs), not a cold Gradle build - generous but well under the provisioning
// ceiling above.
private const val DEPLOY_TIMEOUT_MS = 180_000L

// How often to look for the system install dialog while provisioning runs. Must stay well
// inside CoGo's own 180 s install-confirm fail-fast so the tap lands before it gives up.
private const val INSTALL_CONFIRM_POLL_MS = 1_000L

/**
 * Kaspresso end-to-end coverage for the Quick Build pipeline (ADFA-4128): scaffold a
 * project, provision a live session, then drive a save through to a proxy-app-acknowledged
 * deploy. Complements [QuickBuildSmokeTest], which covers the toolbar/dialog/banner
 * surfaces without running a build to completion.
 *
 * - [test_projectSetupReachesReadyAtGenerationZero]: New Project wizard -> real Quick
 *   Build tap -> session reaches [QuickBuildSessionState.Ready] at generation 0 (setup
 *   build ran, proxy app installed).
 * - [test_saveAdvancesGenerationAndDeployIsAcknowledged]: with a Ready session, a plain
 *   `java.io` write to a Kotlin source file (the save that fires the on-device file
 *   watcher) drives a real build whose generation strictly advances and lands in
 *   [QuickBuildSessionState.Deployed] - which, per [org.appdevforall.cotg.quickbuild.service.PayloadDeployer.deployPayload],
 *   only happens once the proxy app's reload acknowledgement comes back over the binder
 *   channel. Observing that state IS proxy-app-confirmed evidence even though nothing
 *   inside the proxy app's own UI is asserted here.
 *
 * Runs after [EndToEndTest] in `OrderedTestSuite`: assumes onboarding is complete (same
 * assumption [QuickBuildSmokeTest] documents).
 */
@RunWith(AndroidJUnit4::class)
class QuickBuildPipelineTest : TestCase() {
	private val targetContext
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	private var hadExperimentsFlag = false

	/**
	 * Fake occupant of the project's real applicationId. `installed=false` reads as "the
	 * slot is empty", which is what makes a real Quick Build tap proceed straight to
	 * provisioning without the clobber-confirm dialog.
	 *
	 * Copied in [QuickBuildSmokeTest] rather than shared. Nothing forces that - the
	 * `helper` package next door is shared test code - so it is worth folding into a
	 * helper the next time these run on a device.
	 */
	private class FakeInstalledPackages : InstalledPackages {
		@Volatile var installed: Boolean = false

		override fun uid(packageName: String): Int? = if (installed) 12345 else null

		override fun lastUpdateTime(packageName: String): Long? = null

		override fun apkFile(packageName: String): File? = null

		override fun versionCode(packageName: String): Long? = null

		override fun signingCertSha256(packageName: String): String? = null

		override fun appComponentFactory(packageName: String): String? = null
	}

	private val fakePackages = FakeInstalledPackages()
	private var clobberCheckOverridden = false

	@Test
	fun test_projectSetupReachesReadyAtGenerationZero() =
		before {
			hadExperimentsFlag = isExperimentsFlagSet()
			setExperimentsFlagForTest(true)
			IJdkDistributionProvider.getInstance().loadDistributions()
		}.after {
			setExperimentsFlagForTest(hadExperimentsFlag)
			runCatching { GlobalContext.get().get<QuickBuildSessionManager>().restartSession() }
			restoreRealClobberCheckIfOverridden()
		}.run {
			step("Launch app") {
				ActivityScenario.launch(SplashActivity::class.java)
				waitForMainHomeOrEditorUi(device.uiDevice)
			}

			ensureOnHomeScreenBeforeCreateProject()

			step("Create project") {
				clickCreateProjectHomeScreen()
			}
			selectProjectTemplate("Select Empty Activity template", R.string.template_empty)
			selectKotlinLanguage()
			// qb- prefix: on-device automation may only create qb-* project dirs.
			setProjectName("qb-setup")
			fixDerivedPackageName("qb-setup", "qbsetup")
			clickCreateProjectProjectSettings()

			dismissFirstBuildNoticeIfShown()
			assertQuickBuildButtonShown(EDITOR_OPEN_TIMEOUT_MS)

			waitForProjectSync()

			step("Real tap starts provisioning without a clobber confirm") {
				// Slot empty: the tap must proceed straight into provisioning.
				overrideClobberCheckWithEmptySlot()
				tapQuickBuildButton()
			}

			step("Session reaches Ready at generation 0 (setup build ran, proxy app installed)") {
				val readyState = awaitReadyConfirmingProxyAppInstall()
				assertEquals(
					"Provisioning must land a fresh project's session at generation 0",
					0L,
					readyState.generation,
				)
				assertTrue("A Ready session must carry no failure fresh out of provisioning", readyState.lastFailure == null)
			}
		}

	@Test
	fun test_saveAdvancesGenerationAndDeployIsAcknowledged() =
		before {
			hadExperimentsFlag = isExperimentsFlagSet()
			setExperimentsFlagForTest(true)
			IJdkDistributionProvider.getInstance().loadDistributions()
		}.after {
			setExperimentsFlagForTest(hadExperimentsFlag)
			runCatching { GlobalContext.get().get<QuickBuildSessionManager>().restartSession() }
			restoreRealClobberCheckIfOverridden()
		}.run {
			step("Launch app") {
				ActivityScenario.launch(SplashActivity::class.java)
				waitForMainHomeOrEditorUi(device.uiDevice)
			}

			ensureOnHomeScreenBeforeCreateProject()

			step("Create project") {
				clickCreateProjectHomeScreen()
			}
			selectProjectTemplate("Select Empty Activity template", R.string.template_empty)
			selectKotlinLanguage()
			setProjectName("qb-deploy")
			fixDerivedPackageName("qb-deploy", "qbdeploy")
			clickCreateProjectProjectSettings()

			dismissFirstBuildNoticeIfShown()
			assertQuickBuildButtonShown(EDITOR_OPEN_TIMEOUT_MS)

			waitForProjectSync()

			step("Establish a Ready session via a real tap") {
				overrideClobberCheckWithEmptySlot()
				tapQuickBuildButton()
			}

			// step() returns Unit (Kaspresso's TestContext.step signature), so the value
			// crosses the step boundary via this captured var rather than a step "result".
			var readyGeneration = -1L
			step("Wait for Ready") {
				readyGeneration = awaitReadyConfirmingProxyAppInstall().generation
			}

			step("Write a change to a Kotlin source file via java.io - the save that fires the watcher") {
				val projectDir = File(IProjectManager.getInstance().projectDirPath)
				assertTrue("No open project directory", projectDir.isDirectory)
				val target = findKotlinSourceFile(projectDir)
				val original = target.readText()
				val edited = original + "\n// ADFA-4128 quick-build save-to-deploy test marker\n"
				// In-place truncate + write (matches how CoGo's own editor saves, per
				// WatchFilter's KDoc) rather than a temp-file-plus-rename, so the watcher
				// sees a plain content change on the same path instead of a rename event.
				FileOutputStream(target, false).use { stream ->
					stream.write(edited.toByteArray(Charsets.UTF_8))
				}
			}

			step("Generation advances and the deploy is acknowledged by the proxy app") {
				// Deployed is only reached from SessionEvent.BuildSucceeded, which in turn
				// is only dispatched from PayloadDeployer.deployPayload after
				// DeployResult.Reloaded - the proxy app's own reportReloaded acknowledgement
				// arriving back over the binder channel. Observing this state is therefore
				// proxy-app-confirmed evidence of the deploy, without asserting anything
				// inside the proxy app's UI.
				val deployedState =
					runBlocking {
						withTimeout(DEPLOY_TIMEOUT_MS) {
							sessionManager().state.first {
								it is QuickBuildSessionState.Deployed && it.generation > readyGeneration
							}
						}
					} as QuickBuildSessionState.Deployed
				assertTrue(
					"Deployed generation (${deployedState.generation}) must strictly advance past " +
						"the Ready baseline ($readyGeneration)",
					deployedState.generation > readyGeneration,
				)
			}
		}

	/**
	 * Waits for the session to reach [QuickBuildSessionState.Ready], tapping the system
	 * package-installer's confirm button whenever it appears.
	 *
	 * Provisioning installs the proxy app through Android's installer UI, which requires a
	 * human tap. Left unanswered, CoGo's own install-confirm fail-fast gives up after 180 s
	 * and drops the session back out of provisioning, so an unattended run MUST drive that
	 * dialog or it can never reach Ready.
	 *
	 * The Flow is collected on a background coroutine (so a fast Ready -> Building warm-compile
	 * transition can't be missed the way polling `state.value` would miss it) while this, the
	 * instrumentation thread, keeps sole ownership of UiAutomator.
	 */
	private fun TestContext<Unit>.awaitReadyConfirmingProxyAppInstall(): QuickBuildSessionState.Ready {
		val d = device.uiDevice
		val ready = AtomicReference<QuickBuildSessionState.Ready?>(null)
		val scope = CoroutineScope(Dispatchers.Default)
		val collector =
			scope.launch {
				val state = sessionManager().state.first { it is QuickBuildSessionState.Ready }
				ready.set(state as QuickBuildSessionState.Ready)
			}
		try {
			val deadline = System.currentTimeMillis() + PROVISIONING_READY_TIMEOUT_MS
			while (ready.get() == null && System.currentTimeMillis() < deadline) {
				val confirm =
					d.findObject(
						UiSelector()
							.packageNameMatches(".*packageinstaller.*|.*permissioncontroller.*")
							.textMatches("(?i)install"),
					)
				if (confirm.exists()) {
					runCatching { confirm.click() }
				}
				Thread.sleep(INSTALL_CONFIRM_POLL_MS)
			}
		} finally {
			collector.cancel()
		}
		return ready.get()
			?: error("Session never reached Ready; last state was ${sessionManager().state.value}")
	}

	private fun sessionManager(): QuickBuildSessionManager = GlobalContext.get().get()

	private fun overrideClobberCheckWithEmptySlot() {
		loadKoinModules(module { single<QuickBuildClobberCheck> { QuickBuildClobberCheck(fakePackages) } })
		clobberCheckOverridden = true
		fakePackages.installed = false
	}

	private fun restoreRealClobberCheckIfOverridden() {
		if (clobberCheckOverridden) {
			// Re-bind the real PackageManager-backed check so later tests see production
			// behavior instead of the fake.
			loadKoinModules(
				module {
					single { QuickBuildClobberCheck(AndroidInstalledPackages(targetContext)) }
				},
			)
		}
	}

	private fun TestContext<Unit>.fixDerivedPackageName(
		projectName: String,
		packageSuffix: String,
	) {
		step("Fix the auto-derived package name (hyphen is not a valid package char)") {
			// appNameToPackageName derives "com.example.$projectName", which fails the
			// PACKAGE constraint and silently blocks the Create button. Overwrite it.
			val d = device.uiDevice
			val derived = d.findObject(UiSelector().text("com.example.$projectName"))
			check(derived.waitForExists(PACKAGE_FIELD_TIMEOUT_MS)) { "Auto-derived package field not found" }
			setAccessibilityEditText("com.example.$projectName", "com.example.$packageSuffix", "package name")
			d.waitForIdle()
		}
	}

	private fun TestContext<Unit>.waitForProjectSync() {
		step("Wait for project sync (real applicationId available)") {
			// The clobber gate and the real proxy app build both need the selected
			// variant's applicationId, which only exists after the project's Gradle sync
			// completes. Same ceiling as the existing init scenario; polls a state seam
			// instead of UI text.
			val deadline = System.currentTimeMillis() + PROJECT_SYNC_TIMEOUT_MS
			var appId: String? = null
			while (System.currentTimeMillis() < deadline && appId == null) {
				appId =
					runCatching {
						IProjectManager
							.getInstance()
							.getAndroidAppModules()
							.firstOrNull()
							?.getSelectedVariant()
							?.mainArtifact
							?.applicationId
					}.getOrNull()
						?.takeIf { it.isNotBlank() }
				if (appId == null) {
					Thread.sleep(PROJECT_SYNC_POLL_MS)
				}
			}
			check(appId != null) { "Project sync never produced an applicationId" }
		}
	}

	/** First non-build Kotlin source file under [projectDir] - the wizard's MainActivity.kt. */
	private fun findKotlinSourceFile(projectDir: File): File =
		projectDir
			.walkTopDown()
			.firstOrNull { file ->
				file.isFile &&
					file.extension == "kt" &&
					file
						.relativeTo(projectDir)
						.path
						.split(File.separatorChar)
						.none { it == "build" }
			} ?: error("No Kotlin source file found under $projectDir")
}
