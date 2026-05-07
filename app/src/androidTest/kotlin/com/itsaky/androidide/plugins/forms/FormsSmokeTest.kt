/*
 * Kaspresso smoke test for the Forms plugin (ADFA-2435).
 *
 * Scope: confirm the runtime fix from commits `58b788f58` (Host wizard as
 * Fragment; wrap inflater in plugin fragments) and `12ca5aa98`-equivalent
 * for Maps. The class of bugs both Forms and Maps shipped on 2026-05-07:
 *
 *   - `<activity>` declared in plugin manifest → `ActivityNotFoundException`
 *     when the host tried to launch it (plugin APKs load via DexClassLoader
 *     so the activity never enters the host's PackageManager).
 *   - Plugin Fragment forgot to wrap its inflater via
 *     `PluginFragmentHelper.getPluginInflater(...)` →
 *     `Resources$NotFoundException` on layout inflation.
 *
 * This test walks: open editor → tap "Form schema" sidebar entry →
 * verify SchemaPanelFragment inflates → tap "📷 Capture form from photo" →
 * verify wizard Step 1 fragment loads → press back → confirm clean exit.
 *
 * Integration path (per docs/notes/team-test-conventions.md):
 *
 *  1. Build the plugin's `.cgp`:
 *     `./gradlew :forms-plugin:assemblePluginDebug`
 *  2. Copy `forms-plugin/build/plugin/forms-plugin-debug.cgp` to
 *     `app/src/androidTest/assets/plugins/forms-plugin-debug.cgp`.
 *  3. Remove the `@Ignore` below.
 *
 * The companion FormsFragmentInflationTest.kt is the fast, always-on
 * test — it exercises the same runtime fix without driving the full
 * host UI.
 */
package com.itsaky.androidide.plugins.forms

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.plugins.forms.screens.FormsScreen
import com.itsaky.androidide.plugins.testsupport.LogcatWatcher
import com.itsaky.androidide.plugins.testsupport.PluginTestSetup
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormsSmokeTest : TestCase() {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        PluginTestSetup.clearPluginsDir(targetContext)
    }

    @Test
    fun test_formsSchemaPanelLoadsCleanly() = run {
        val watcher = LogcatWatcher.start("FormsSmoke")

        step("Stage forms plugin into host filesDir") {
            PluginTestSetup.installFromAndroidTestAssets(
                targetContext = targetContext,
                assetPath = "plugins/forms-plugin-debug.cgp",
                destFileName = "forms-plugin-debug.cgp",
            )
        }

        step("Launch host (SplashActivity will trigger plugin load)") {
            ActivityScenario.launch(SplashActivity::class.java)
            Thread.sleep(2_000)
        }

        step("Verify Form schema sidebar entry is registered") {
            check(FormsScreen.isSidebarEntryVisible(device.uiDevice)) {
                "Form schema sidebar entry not visible after plugin load. " +
                    "The plugin's getSideMenuItems() registration may have " +
                    "failed or PluginManager skipped the .cgp."
            }
        }

        step("Tap Form schema and verify SchemaPanelFragment inflates") {
            FormsScreen.tapSidebarEntry(device.uiDevice)
            check(FormsScreen.isSchemaPanelInflated(device.uiDevice)) {
                "SchemaPanelFragment did not inflate after sidebar tap. " +
                    "Likely cause: fragment inflater was not wrapped via " +
                    "PluginFragmentHelper.getPluginInflater (the bug fixed " +
                    "in commit 58b788f58). Check logcat for " +
                    "Resources\$NotFoundException."
            }
        }

        step("Tap Capture form from photo and verify wizard Step 1 loads") {
            FormsScreen.tapCaptureButton(device.uiDevice)
            check(FormsScreen.isWizardStep1Inflated(device.uiDevice)) {
                "Wizard Step 1 fragment did not load. The wizard now " +
                    "lives inside the panel's childFragmentManager — if " +
                    "this fails, either the wizard's getInflater wrap is " +
                    "missing or the host's tab swap is broken."
            }
        }

        step("Press back and verify return to schema panel") {
            FormsScreen.pressBack(device.uiDevice)
            // After back, the schema panel should be visible again.
            check(FormsScreen.isSchemaPanelInflated(device.uiDevice)) {
                "Schema panel did not re-appear after pressing back from " +
                    "the wizard. Could indicate the panel was destroyed " +
                    "instead of stashed, or that the wizard's onBackPressed " +
                    "handler crashed."
            }
        }

        step("Cancel-path: open wizard and press back without crashing") {
            FormsScreen.tapCaptureButton(device.uiDevice)
            check(FormsScreen.isWizardStep1Inflated(device.uiDevice)) {
                "Wizard did not re-open on second tap of Capture button."
            }
            FormsScreen.pressBack(device.uiDevice)
            // No crash assertion — the logcat watcher catches uncaught
            // exceptions below.
        }

        step("Assert no fatal plugin errors in logcat") {
            watcher.assertNoFatalPluginErrors()
        }
    }
}
