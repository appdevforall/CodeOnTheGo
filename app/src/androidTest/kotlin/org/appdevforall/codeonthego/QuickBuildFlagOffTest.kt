package org.appdevforall.codeonthego

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.appdevforall.codeonthego.activities.SplashActivity
import org.appdevforall.codeonthego.activities.editor.EditorHandlerActivity
import org.appdevforall.codeonthego.app.configuration.IJdkDistributionProvider
import org.appdevforall.codeonthego.helper.isExperimentsFlagSet
import org.appdevforall.codeonthego.helper.setExperimentsFlagForTest
import org.appdevforall.codeonthego.helper.waitForMainHomeOrEditorUi
import org.appdevforall.codeonthego.screens.QuickBuildScreen.assertQuickBuildButtonAbsent
import org.appdevforall.codeonthego.screens.QuickBuildScreen.assertQuickBuildButtonShown
import org.appdevforall.codeonthego.utils.EditorActivityActions
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.Test
import org.junit.runner.RunWith

private const val TOOLBAR_TIMEOUT_MS = 15_000L

/**
 * The shipping-state gate for Quick Build (ADFA-4128, manual test T13): with no
 * `CodeOnTheGo.exp` flag file on the device, the feature must be invisible.
 *
 * The gate is a single read of [org.appdevforall.codeonthego.utils.FeatureFlags.isExperimentsEnabled]
 * at [EditorActivityActions.register], so this drives that decision directly instead of
 * restarting the process: flip the flag, re-register, rebuild the toolbar, look. That also
 * makes the test honest about what it covers - the registration site, not the process-start
 * caching around it.
 *
 * Both directions run in one test on purpose. An absence assertion alone passes when the
 * accessibility selector rots or the toolbar simply never rendered, so the flag-on step
 * ahead of it is load-bearing, not decoration.
 *
 * Runs after [QuickBuildSmokeTest] in [OrderedTestSuite], which leaves the editor open on a
 * synced project - this test needs a populated editor toolbar and creates no project of its
 * own.
 */
@RunWith(AndroidJUnit4::class)
class QuickBuildFlagOffTest : TestCase() {
	private var hadExperimentsFlag = false

	@Test
	fun test_noExperimentsFlagHidesQuickBuild() =
		before {
			// A dev device may legitimately have experiments enabled; restore whatever
			// state this test found.
			hadExperimentsFlag = isExperimentsFlagSet()
			IJdkDistributionProvider.getInstance().loadDistributions()
		}.after {
			setExperimentsFlagForTest(hadExperimentsFlag)
			// Leave the toolbar matching the restored flag so a later test does not
			// inherit this one's registry.
			runCatching { rebuildEditorToolbar() }
		}.run {
			step("Launch app") {
				ActivityScenario.launch(SplashActivity::class.java)
				waitForMainHomeOrEditorUi(device.uiDevice)
			}

			step("Experiments on: the toolbar carries Quick Build") {
				setExperimentsFlagForTest(true)
				rebuildEditorToolbar()
				assertQuickBuildButtonShown(TOOLBAR_TIMEOUT_MS)
			}

			step("Experiments off: the toolbar drops Quick Build") {
				setExperimentsFlagForTest(false)
				rebuildEditorToolbar()
				assertQuickBuildButtonAbsent(TOOLBAR_TIMEOUT_MS)
			}
		}

	/**
	 * Re-runs action registration and repopulates the toolbar, which is what an editor
	 * launch does. On the main thread: both touch the actions registry and the toolbar
	 * views.
	 */
	private fun rebuildEditorToolbar() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val activity = resumedEditorActivity()
		instrumentation.runOnMainSync {
			EditorActivityActions.register(activity)
			activity.prepareOptionsMenu()
		}
		instrumentation.waitForIdleSync()
	}

	private fun resumedEditorActivity(): EditorHandlerActivity =
		device.activities.getResumed() as? EditorHandlerActivity
			?: error("Resumed activity is not the editor; this test needs an open project")
}
