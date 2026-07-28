package com.itsaky.androidide

import android.content.Intent
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
import com.itsaky.androidide.quickbuild.QuickBuildErrorJumpEvent
import com.itsaky.androidide.quickbuild.QuickBuildJumpActivity
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.setProjectName
import com.itsaky.androidide.screens.QuickBuildScreen.assertQuickBuildButtonShown
import com.itsaky.androidide.screens.QuickBuildScreen.dismissFirstBuildNoticeIfShown
import com.itsaky.androidide.screens.QuickBuildScreen.dismissQuickBuildDropdown
import com.itsaky.androidide.screens.QuickBuildScreen.longPressOpensQuickBuildDropdown
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val EDITOR_OPEN_TIMEOUT_MS = 60_000L
private const val PACKAGE_FIELD_TIMEOUT_MS = 3_000L
private const val JUMP_EVENT_TIMEOUT_S = 5L

/**
 * Kaspresso smoke for the Quick Build surfaces added by ADFA-4128 (plan A2/E3/A1): the
 * lightning-bolt toolbar action (via [com.itsaky.androidide.screens.QuickBuildScreen]),
 * its long-press split-button dropdown, and the QuickBuildJumpActivity trampoline the
 * test app's error overlay fires. Exercises the surfaces only - it never starts a
 * quick-build session (that needs an installed test app + warm daemon; covered by the
 * on-device QA walk).
 *
 * Runs after [EndToEndTest] in [OrderedTestSuite]: assumes onboarding is complete.
 */
@RunWith(AndroidJUnit4::class)
class QuickBuildSmokeTest : TestCase() {
	private val targetContext
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	private var hadExperimentsFlag = false

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
		}

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
