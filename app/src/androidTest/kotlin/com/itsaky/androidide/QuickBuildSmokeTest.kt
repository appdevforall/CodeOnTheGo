package com.itsaky.androidide

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.helper.ensureOnHomeScreenBeforeCreateProject
import com.itsaky.androidide.helper.isExperimentsFlagSet
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.helper.setAccessibilityEditText
import com.itsaky.androidide.helper.setExperimentsFlagForTest
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.quickbuild.AndroidInstalledPackages
import com.itsaky.androidide.quickbuild.QuickBuildErrorJumpEvent
import com.itsaky.androidide.quickbuild.QuickBuildJumpActivity
import com.itsaky.androidide.screens.ErrorBannerScreen.assertErrorBannerShown
import com.itsaky.androidide.screens.ErrorBannerScreen.dismissErrorBannerViaButton
import com.itsaky.androidide.screens.ErrorBannerScreen.dismissErrorBannerViaSwipe
import com.itsaky.androidide.screens.ErrorBannerScreen.dismissErrorBannerViaTapOnBar
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.setProjectName
import com.itsaky.androidide.screens.QuickBuildScreen.acceptClobberConfirm
import com.itsaky.androidide.screens.QuickBuildScreen.assertClobberConfirmShown
import com.itsaky.androidide.screens.QuickBuildScreen.assertQuickBuildButtonShown
import com.itsaky.androidide.screens.QuickBuildScreen.assertQuickBuildButtonShowsStop
import com.itsaky.androidide.screens.QuickBuildScreen.declineClobberConfirm
import com.itsaky.androidide.screens.QuickBuildScreen.dismissFirstBuildNoticeIfShown
import com.itsaky.androidide.screens.QuickBuildScreen.dismissQuickBuildDropdown
import com.itsaky.androidide.screens.QuickBuildScreen.longPressOpensQuickBuildDropdown
import com.itsaky.androidide.screens.QuickBuildScreen.tapQuickBuildButton
import com.itsaky.androidide.utils.flashError
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.cotg.quickbuild.domain.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.service.InstalledPackages
import org.appdevforall.cotg.quickbuild.service.QuickBuildClobberCheck
import org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val EDITOR_OPEN_TIMEOUT_MS = 60_000L
private const val PACKAGE_FIELD_TIMEOUT_MS = 3_000L
private const val JUMP_EVENT_TIMEOUT_S = 5L

// First sync on a cold daemon has been measured past 5 minutes on CI emulators
// (see InitializationProjectAndCancelingBuildScenario); same ceiling here.
private const val PROJECT_INIT_TIMEOUT_MS = 15 * 60 * 1000L
private const val PROJECT_INIT_POLL_MS = 1_000L

private const val SESSION_START_TIMEOUT_MS = 60_000L
private const val STOP_AFFORDANCE_TIMEOUT_MS = 15_000L
private const val SESSION_TEARDOWN_TIMEOUT_MS = 120_000L
private const val INSTALLER_DIALOG_CHECK_MS = 2_000L

private const val BANNER_MESSAGE = "Quick Build smoke: injected error banner"

/**
 * Kaspresso smoke for the Quick Build surfaces added by ADFA-4128 (plan A2/E3/A1):
 * - the lightning-bolt toolbar action (via [com.itsaky.androidide.screens.QuickBuildScreen])
 *   and its long-press split-button dropdown;
 * - the QuickBuildJumpActivity trampoline the proxy app's error overlay fires;
 * - the indefinite error banner (the surface `userMessages` renders through `flashError`)
 *   and its three dismiss paths: Dismiss button, tap-anywhere, swipe;
 * - the confirm-on-switch ("rebaseline/reinstall") dialog, driven through
 *   [EditorHandlerActivity.ensureQuickBuildClobberConfirmed] with a fake
 *   [InstalledPackages] so it renders deterministically without installing anything;
 * - a real tap on the button: the session leaves Idle (status -> Provisioning) and the
 *   button flips to the stop affordance, then "Restart session" tears it back down.
 *
 * Determinism notes: the banner and dialog steps drive state seams directly (no build
 * runs, nothing installs). The tap step starts a REAL provisioning setup build; the test
 * only asserts the status flip and then restarts the session, so the build never runs to
 * completion. Residual flakiness risk: if provisioning fails within the assertion window
 * (broken toolchain on the test device), the status lands on Failed instead of
 * Provisioning and the step fails - that is a genuine signal, not noise. The project-sync
 * wait mirrors the 15-minute ceiling the existing init scenario uses.
 *
 * Runs after [EndToEndTest] in [OrderedTestSuite]: assumes onboarding is complete.
 */
@RunWith(AndroidJUnit4::class)
class QuickBuildSmokeTest : TestCase() {
	private val targetContext
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	private var hadExperimentsFlag = false

	/**
	 * Fake occupant of the project's real applicationId. `installed=true` with a null
	 * component factory reads as "a Standard-Run build occupies the slot", which is
	 * exactly the state that must pop the clobber confirm (RealIdInstall's rules).
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
	fun test_quickBuildSurfaces() =
		before {
			// The toolbar action only registers when experiments are enabled. Snapshot
			// the pre-test flag state so the after-block restores it - a dev device may
			// legitimately have experiments enabled outside this test.
			hadExperimentsFlag = isExperimentsFlagSet()
			setExperimentsFlagForTest(true)
			// On an already-provisioned device, OnboardingActivity skips its async
			// JDK-distribution reload in test mode (onResume), so isSetupCompleted()
			// would stay false and the app would park on the welcome slide forever.
			// Load synchronously up front; harmless when run after EndToEndTest.
			IJdkDistributionProvider.getInstance().loadDistributions()
		}.after {
			setExperimentsFlagForTest(hadExperimentsFlag)
			// Leave no live session behind: harmless no-op from Idle.
			runCatching { GlobalContext.get().get<QuickBuildSessionManager>().restartSession() }
			if (clobberCheckOverridden) {
				// Re-bind the real PackageManager-backed check so later tests see
				// production behavior instead of the fake.
				loadKoinModules(
					module {
						single { QuickBuildClobberCheck(AndroidInstalledPackages(targetContext)) }
					},
				)
			}
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
			// qb- prefix: on-device automation may only create qb-* project dirs.
			setProjectName("qb-smoke")
			step("Fix the auto-derived package name (hyphen is not a valid package char)") {
				// appNameToPackageName derives "com.example.qb-smoke", which fails the
				// PACKAGE constraint and silently blocks the Create button. Overwrite it.
				val d = device.uiDevice
				val derived = d.findObject(UiSelector().text("com.example.qb-smoke"))
				check(derived.waitForExists(PACKAGE_FIELD_TIMEOUT_MS)) { "Auto-derived package field not found" }
				setAccessibilityEditText("com.example.qb-smoke", "com.example.qbsmoke", "package name")
				d.waitForIdle()
			}
			clickCreateProjectProjectSettings()

			dismissFirstBuildNoticeIfShown()
			assertQuickBuildButtonShown(EDITOR_OPEN_TIMEOUT_MS)
			longPressOpensQuickBuildDropdown()
			dismissQuickBuildDropdown()

			step("Indefinite error banner renders and dismisses three ways") {
				// Drives the exact surface QuickBuildSessionManager.userMessages renders
				// through (ProjectHandlerActivity collects it into flashError). Injected
				// directly so the step needs no real build failure.
				val activity = resumedEditorActivity()
				activity.flashError(BANNER_MESSAGE)
				assertErrorBannerShown(BANNER_MESSAGE)
				dismissErrorBannerViaButton(BANNER_MESSAGE)

				activity.flashError(BANNER_MESSAGE)
				assertErrorBannerShown(BANNER_MESSAGE)
				dismissErrorBannerViaTapOnBar(BANNER_MESSAGE)

				activity.flashError(BANNER_MESSAGE)
				assertErrorBannerShown(BANNER_MESSAGE)
				dismissErrorBannerViaSwipe(BANNER_MESSAGE)
			}

			step("Jump trampoline rejects a file outside the project") {
				val latch = CountDownLatch(1)
				val subscriber = JumpEventSubscriber(latch)
				EventBus.getDefault().register(subscriber)
				try {
					// Exists on disk (created by the before-block) but outside the project.
					startJumpActivity("/sdcard/Download/CodeOnTheGo.exp")
					assertFalse(
						"A file outside the open project must not post a jump event",
						latch.await(3, TimeUnit.SECONDS),
					)
				} finally {
					EventBus.getDefault().unregister(subscriber)
				}
			}

			step("Jump trampoline posts the event for a project file") {
				val projectDir = File(IProjectManager.getInstance().projectDirPath)
				assertTrue("No open project directory", projectDir.isDirectory)
				val target =
					projectDir
						.walkTopDown()
						.firstOrNull { it.isFile && it.extension in SOURCE_EXTENSIONS }
						?: error("No source file found under $projectDir")

				val latch = CountDownLatch(1)
				val subscriber = JumpEventSubscriber(latch)
				EventBus.getDefault().register(subscriber)
				try {
					startJumpActivity(target.absolutePath)
					assertTrue(
						"Jump event for a valid project file was not posted",
						latch.await(JUMP_EVENT_TIMEOUT_S, TimeUnit.SECONDS),
					)
					assertEquals(target.canonicalFile, subscriber.received.get()?.file)
				} finally {
					EventBus.getDefault().unregister(subscriber)
				}
			}

			step("Wait for project sync (real applicationId available)") {
				// The clobber gate needs the selected variant's applicationId, which only
				// exists after the project's Gradle sync completes. Same ceiling as the
				// existing init scenario; polls a state seam instead of UI text.
				val deadline = System.currentTimeMillis() + PROJECT_INIT_TIMEOUT_MS
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
						Thread.sleep(PROJECT_INIT_POLL_MS)
					}
				}
				check(appId != null) { "Project sync never produced an applicationId" }
			}

			step("Rebaseline/reinstall confirm renders and honors decline then accept") {
				// Override the clobber check with a fake occupant so the dialog is
				// reachable without actually installing anything under the real id.
				loadKoinModules(module { single<QuickBuildClobberCheck> { QuickBuildClobberCheck(fakePackages) } })
				clobberCheckOverridden = true
				fakePackages.installed = true

				val activity = resumedEditorActivity()
				val instrumentation = InstrumentationRegistry.getInstrumentation()
				val confirmed = AtomicBoolean(false)

				instrumentation.runOnMainSync {
					activity.ensureQuickBuildClobberConfirmed { confirmed.set(true) }
				}
				assertClobberConfirmShown()
				declineClobberConfirm()
				assertFalse("Decline must not run the confirmed continuation", confirmed.get())

				instrumentation.runOnMainSync {
					activity.ensureQuickBuildClobberConfirmed { confirmed.set(true) }
				}
				assertClobberConfirmShown()
				acceptClobberConfirm()
				instrumentation.waitForIdleSync()
				assertTrue("Accept must run the confirmed continuation", confirmed.get())
			}

			step("Tap starts a session: status flips and the button becomes stop") {
				// Fake reads "slot empty": the tap must proceed without a confirm.
				fakePackages.installed = false
				val sessionManager = GlobalContext.get().get<QuickBuildSessionManager>()

				tapQuickBuildButton()
				val status =
					runBlocking {
						withTimeout(SESSION_START_TIMEOUT_MS) {
							sessionManager.status.first { it != QuickBuildStatus.Hidden }
						}
					}
				assertTrue(
					"Tap must start provisioning; status was $status",
					status is QuickBuildStatus.Provisioning,
				)
				assertQuickBuildButtonShowsStop(STOP_AFFORDANCE_TIMEOUT_MS)
			}

			step("Restart session tears back down to idle") {
				val sessionManager = GlobalContext.get().get<QuickBuildSessionManager>()
				sessionManager.restartSession()
				runBlocking {
					withTimeout(SESSION_TEARDOWN_TIMEOUT_MS) {
						sessionManager.status.first { it == QuickBuildStatus.Hidden }
					}
				}
				// Defensive: if provisioning raced far enough to fire the proxy-app
				// install confirm (prewarm already warm), dismiss the system dialog.
				val d = device.uiDevice
				val installer =
					d.findObject(
						UiSelector().packageNameMatches(".*packageinstaller.*|.*permissioncontroller.*"),
					)
				if (installer.waitForExists(INSTALLER_DIALOG_CHECK_MS)) {
					val cancel = d.findObject(UiSelector().textMatches("(?i)cancel"))
					if (cancel.exists()) cancel.click() else d.pressBack()
				}
			}
		}

	private fun resumedEditorActivity(): EditorHandlerActivity =
		device.activities.getResumed() as? EditorHandlerActivity
			?: error("Resumed activity is not the editor")

	private fun startJumpActivity(path: String) {
		val intent =
			Intent(targetContext, QuickBuildJumpActivity::class.java)
				.setAction(QuickBuildJumpActivity.ACTION_JUMP_TO_ERROR)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.putExtra(QuickBuildJumpActivity.EXTRA_FILE, path)
				.putExtra(QuickBuildJumpActivity.EXTRA_LINE, 1)
				.putExtra(QuickBuildJumpActivity.EXTRA_COLUMN, 1)
		targetContext.startActivity(intent)
	}

	class JumpEventSubscriber(
		private val latch: CountDownLatch,
	) {
		val received = AtomicReference<QuickBuildErrorJumpEvent>()

		@Subscribe
		fun onJump(event: QuickBuildErrorJumpEvent) {
			received.set(event)
			latch.countDown()
		}
	}

	companion object {
		private val SOURCE_EXTENSIONS = setOf("kt", "java", "xml", "kts", "gradle")
	}
}
