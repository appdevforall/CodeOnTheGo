/*
 * Kaspresso smoke test for the random-xkcd plugin (ADFA-2433).
 *
 * Scope: confirm the plugin's runtime — sidebar/tab → fragment loads, no
 * `Resources$NotFoundException`, no `ActivityNotFoundException`. This is
 * the durable merge-gate that catches the class of P1s Forms+Maps shipped
 * on 2026-05-07 (Activities → Fragments + missing PluginFragmentHelper
 * inflater wrap). The xkcd plugin uses the bottom-sheet tab surface, not
 * a sidebar, but the inflation failure mode is identical.
 *
 * Integration path (per docs/notes/team-test-conventions.md Layer 2 +
 * Layer 3 Tier 3):
 *
 *  1. The host APK installs plugins from `<filesDir>/plugins/[name].cgp` at
 *     startup (PluginManager.loadPlugins). Tests stage a debug `.cgp` into
 *     that directory before driving the UI.
 *  2. Build the plugin's `.cgp` once via
 *     `./gradlew :random-xkcd-plugin:assemblePluginDebug`. The output
 *     lands at `random-xkcd-plugin/build/plugin/random-xkcd-debug.cgp`.
 *  3. Either bundle that file as an `androidTest` asset (preferred for
 *     deterministic CI) or `adb push` it into place from CI before the
 *     test run. See PluginTestSetup for both paths.
 *
 * Until the .cgp is bundled, the test is `@Ignore`'d — but the page-object
 * + flow is in place so flipping it on is a one-line change once the
 * androidTest asset is added.
 *
 * The companion XkcdFragmentInflationTest.kt is the fast, always-on
 * test — it exercises the same runtime fix (plugin context resolution,
 * fragment inflation) without needing the full host UI.
 */
package com.itsaky.androidide.plugins.xkcd

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.plugins.testsupport.LogcatWatcher
import com.itsaky.androidide.plugins.testsupport.PluginTestSetup
import com.itsaky.androidide.plugins.xkcd.screens.XkcdScreen
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore(
    "Requires the random-xkcd plugin's debug .cgp bundled at " +
        "androidTest/assets/plugins/random-xkcd-debug.cgp. Build via " +
        "./gradlew :random-xkcd-plugin:assemblePluginDebug, then copy and " +
        "remove this @Ignore. See file header + PluginTestSetup for the " +
        "full integration path."
)
class XkcdSmokeTest : TestCase() {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        // Keep the device state clean between runs — relevant when the
        // same Firebase Test Lab matrix entry is reused across builds.
        PluginTestSetup.clearPluginsDir(targetContext)
    }

    @Test
    fun test_xkcdSidebarTabLoadsCleanly() = run {
        val watcher = LogcatWatcher.start("XkcdSmoke")

        step("Stage random-xkcd plugin into host filesDir") {
            PluginTestSetup.installFromAndroidTestAssets(
                targetContext = targetContext,
                assetPath = "plugins/random-xkcd-debug.cgp",
                destFileName = "random-xkcd-debug.cgp",
            )
        }

        step("Launch host (SplashActivity will trigger plugin load)") {
            ActivityScenario.launch(SplashActivity::class.java)
            // The full host onboarding flow runs in EndToEndTest — we
            // assume the device is past onboarding (Firebase Test Lab
            // CI handles first-run setup once). For local runs, prime the
            // device by running EndToEndTest first.
            Thread.sleep(2_000)
        }

        step("Open the bottom-sheet to surface plugin tabs") {
            // The bottom-sheet handle is the project actions toolbar.
            // Drag-to-expand or long-press as needed; for now, a simple
            // accessibility tap is the most stable. UI driving is left
            // to the host helpers that already exist for the editor flow.
            device.uiDevice.waitForIdle(3_000)
        }

        step("Verify XKCD tab is registered") {
            check(XkcdScreen.isXkcdTabVisible(device.uiDevice)) {
                "XKCD bottom-sheet tab not visible after plugin load. The " +
                    "plugin's getEditorTabs() registration may have failed " +
                    "or PluginManager may have skipped the .cgp."
            }
        }

        step("Tap the XKCD tab and verify panel inflates") {
            XkcdScreen.tapXkcdTab(device.uiDevice)
            check(XkcdScreen.isPanelInflated(device.uiDevice)) {
                "XKCD panel root view (xkcd_root) did not appear after " +
                    "tapping the tab. Likely cause: fragment inflation " +
                    "threw Resources\$NotFoundException because the " +
                    "fragment didn't wrap inflater via " +
                    "PluginFragmentHelper.getPluginInflater. Check logcat."
            }
        }

        step("Single-tap the panel — should not crash even if network is offline") {
            XkcdScreen.tapPanel(device.uiDevice)
            // Network call may fail; we don't assert on the result, only
            // that the gesture handler didn't throw. logcat assertion
            // below catches any uncaught Exception.
            device.uiDevice.waitForIdle(2_000)
        }

        step("Assert no fatal plugin errors in logcat") {
            watcher.assertNoFatalPluginErrors()
        }
    }
}
