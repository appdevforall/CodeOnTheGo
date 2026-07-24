package com.itsaky.androidide

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.helper.clickFirstAccessibilityNodeByText
import com.itsaky.androidide.helper.ensureOnHomeScreenBeforeCreateProject
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.helper.setExperimentsFlagForTest
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.quickbuild.QuickBuildErrorJumpEvent
import com.itsaky.androidide.quickbuild.QuickBuildJumpActivity
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.setProjectName
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
private const val DIALOG_TIMEOUT_MS = 3_000L
private const val MENU_TIMEOUT_MS = 5_000L
private const val JUMP_EVENT_TIMEOUT_S = 5L

/**
 * Kaspresso smoke for the Quick Build surfaces added by ADFA-4128 (plan A2/E3/A1): the
 * lightning-bolt toolbar action, its long-press split-button dropdown, and the
 * QuickBuildJumpActivity trampoline the test app's error overlay fires. Exercises the
 * surfaces only - it never starts a quick-build session (that needs an installed test
 * app + warm daemon; covered by the on-device QA walk).
 *
 * Runs after [EndToEndTest] in [OrderedTestSuite]: assumes onboarding is complete.
 */
@RunWith(AndroidJUnit4::class)
class QuickBuildSmokeTest : TestCase() {
	private val targetContext
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun test_quickBuildSurfaces() =
		before {
			// The toolbar action only registers when experiments are enabled.
			setExperimentsFlagForTest(true)
		}.after {
			setExperimentsFlagForTest(false)
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
			setProjectName("QuickBuildSmoke")
			clickCreateProjectProjectSettings()

			step("Editor shows the Quick Build toolbar button") {
				val d = device.uiDevice
				// Dismiss the first-build notice if it appears.
				val okBtn = d.findObject(UiSelector().text("OK").className("android.widget.Button"))
				if (okBtn.waitForExists(DIALOG_TIMEOUT_MS)) {
					clickFirstAccessibilityNodeByText("OK")
					d.waitForIdle()
				}
				// cd_quick_build is the button's contentDescription (REVIEW.md section 8).
				val quickBuild =
					d.findObject(
						UiSelector().description(targetContext.getString(R.string.cd_quick_build)),
					)
				assertTrue(
					"Quick Build toolbar button not found (experiments flag on, editor open)",
					quickBuild.waitForExists(EDITOR_OPEN_TIMEOUT_MS),
				)
			}

			step("Long-press opens the split-button dropdown") {
				val d = device.uiDevice
				val quickBuild =
					d.findObject(
						UiSelector().description(targetContext.getString(R.string.cd_quick_build)),
					)
				quickBuild.longClick()

				listOf(
					targetContext.getString(R.string.quick_build_action_label),
					targetContext.getString(R.string.quick_build_menu_standard_run),
					targetContext.getString(R.string.quick_build_menu_restart_session),
					targetContext.getString(R.string.help),
				).forEach { title ->
					assertTrue(
						"Dropdown item '$title' not shown after long-press",
						d.findObject(UiSelector().text(title)).waitForExists(MENU_TIMEOUT_MS),
					)
				}

				d.pressBack()
				assertTrue(
					"Dropdown did not dismiss on back",
					d
						.findObject(
							UiSelector().text(targetContext.getString(R.string.quick_build_menu_standard_run)),
						).waitUntilGone(MENU_TIMEOUT_MS),
				)
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
