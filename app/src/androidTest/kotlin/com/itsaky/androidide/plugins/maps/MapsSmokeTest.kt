/*
 * Kaspresso smoke test for the GIS / Maps plugin (ADFA-2436).
 *
 * Scope: confirm the runtime fix from commit `12ca5aa98` ("Host regions UI
 * in editor tab; drop plugin Activities (P1)"). The class of bugs the
 * Maps plugin originally shipped:
 *
 *   - `<activity>` declared in plugin manifest → `ActivityNotFoundException`
 *     when the host tried to launch it (plugin APKs load via DexClassLoader
 *     so the activity never enters the host's PackageManager).
 *   - Plugin Fragment forgot to wrap its inflater via
 *     `PluginFragmentHelper.getPluginInflater(...)` →
 *     `Resources$NotFoundException` on layout inflation.
 *
 * This test walks: open editor → tap "Map Regions" sidebar entry →
 * verify RegionManagerFragment inflates → tap "+ Download new region" →
 * verify bbox-picker fragment loads → press back → confirm clean exit.
 *
 * Integration path (per docs/notes/team-test-conventions.md):
 *
 *  1. Build the plugin's `.cgp`:
 *     `./gradlew :gis-plugin:assemblePluginDebug`
 *  2. Copy `gis-plugin/build/plugin/gis-plugin-debug.cgp` to
 *     `app/src/androidTest/assets/plugins/gis-plugin-debug.cgp`.
 *  3. Remove the `@Ignore` below.
 *
 * The companion MapsFragmentInflationTest.kt is the fast, always-on
 * test — it exercises the same runtime fix without driving the full
 * host UI.
 */
package com.itsaky.androidide.plugins.maps

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.plugins.maps.screens.MapsScreen
import com.itsaky.androidide.plugins.testsupport.LogcatWatcher
import com.itsaky.androidide.plugins.testsupport.PluginTestSetup
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapsSmokeTest : TestCase() {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        PluginTestSetup.clearPluginsDir(targetContext)
    }

    @Test
    fun test_mapsRegionsTabLoadsCleanly() = run {
        val watcher = LogcatWatcher.start("MapsSmoke")

        step("Stage gis plugin into host filesDir") {
            PluginTestSetup.installFromAndroidTestAssets(
                targetContext = targetContext,
                assetPath = "plugins/gis-plugin-debug.cgp",
                destFileName = "gis-plugin-debug.cgp",
            )
        }

        step("Launch host (SplashActivity will trigger plugin load)") {
            ActivityScenario.launch(SplashActivity::class.java)
            Thread.sleep(2_000)
        }

        step("Verify Map Regions sidebar entry is registered") {
            check(MapsScreen.isSidebarEntryVisible(device.uiDevice)) {
                "Map Regions sidebar entry not visible after plugin load. " +
                    "The plugin's getSideMenuItems() registration may have " +
                    "failed or PluginManager skipped the .cgp."
            }
        }

        step("Tap Map Regions and verify RegionManagerFragment inflates") {
            MapsScreen.tapSidebarEntry(device.uiDevice)
            check(MapsScreen.isRegionManagerInflated(device.uiDevice)) {
                "RegionManagerFragment did not inflate after sidebar tap. " +
                    "Likely cause: the sidebar tap routed to a plugin " +
                    "Activity (now removed in commit 12ca5aa98) instead " +
                    "of selectPluginTab(). Check logcat for " +
                    "ActivityNotFoundException or Resources\$NotFoundException."
            }
        }

        step("Tap + Download new region and verify bbox-picker loads") {
            MapsScreen.tapDownloadNewRegion(device.uiDevice)
            check(MapsScreen.isBboxPickerInflated(device.uiDevice)) {
                "Bbox picker fragment did not load. The picker is hosted " +
                    "in the same tab via parentFragmentManager.replace; " +
                    "if this fails, either the swap is broken or the " +
                    "picker fragment's inflater wasn't wrapped via " +
                    "PluginFragmentHelper.getPluginInflater."
            }
        }

        step("Press back and verify return to regions panel") {
            MapsScreen.pressBack(device.uiDevice)
            check(MapsScreen.isRegionManagerInflated(device.uiDevice)) {
                "Regions panel did not re-appear after pressing back from " +
                    "the bbox picker. Could indicate the panel was " +
                    "destroyed instead of stashed, or the picker's " +
                    "onBackPressed handler crashed."
            }
        }

        step("Assert no fatal plugin errors in logcat") {
            watcher.assertNoFatalPluginErrors()
        }
    }
}
