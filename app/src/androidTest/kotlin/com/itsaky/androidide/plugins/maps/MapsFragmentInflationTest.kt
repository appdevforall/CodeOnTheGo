/*
 * Plugin-loader-level smoke test for gis-plugin (ADFA-2436).
 *
 * What this catches: the runtime failure modes that ship green from
 * `./gradlew assembleDebug` but blow up the moment a user taps the
 * plugin's UI surface — specifically:
 *
 *   - `Resources$NotFoundException` from a fragment that forgot to wrap
 *     its inflater via `PluginFragmentHelper.getPluginInflater(...)`.
 *   - `ActivityNotFoundException` from a plugin trying to launch its own
 *     `<activity>` (which never enters the host's PackageManager because
 *     plugin APKs load via DexClassLoader). Maps shipped this bug
 *     originally; the fix landed in commit `12ca5aa98` ("Host regions
 *     UI in editor tab; drop plugin Activities (P1)").
 *
 * What this does NOT catch (covered by the companion MapsSmokeTest):
 *
 *   - Sidebar slot registration via host's UI.
 *   - parentFragmentManager-based bbox-picker swap inside the regions tab.
 *
 * Why split: this test runs without launching SplashActivity or
 * navigating the host's onboarding/editor UI. Fast, deterministic, runs
 * on every PR via Firebase Test Lab. The full UI smoke test is gated on
 * the .cgp being bundled as an asset; this test is always-on once the
 * .cgp is present.
 *
 * Until the .cgp is bundled at
 * `app/src/androidTest/assets/plugins/gis-plugin-debug.cgp`, the test
 * is `@Ignore`'d. Build via `./gradlew :gis-plugin:assemblePluginDebug`,
 * copy the result, drop the @Ignore.
 */
package com.itsaky.androidide.plugins.maps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.testsupport.LogcatWatcher
import com.itsaky.androidide.plugins.testsupport.PluginTestSetup
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapsFragmentInflationTest {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        PluginTestSetup.clearPluginsDir(targetContext)
    }

    @Test
    fun test_pluginLoadsAndRegistersWithoutFatalErrors() {
        val watcher = LogcatWatcher.start("MapsInflate")

        // Stage the .cgp into the host's plugins dir.
        val pluginFile = PluginTestSetup.installFromAndroidTestAssets(
            targetContext = targetContext,
            assetPath = "plugins/gis-plugin-debug.cgp",
            destFileName = "gis-plugin-debug.cgp",
        )
        assertThat(pluginFile.exists()).isTrue()

        // Load the plugin via PluginManager. This exercises:
        //   - DexClassLoader path
        //   - Manifest parsing (mainClass resolution + sidebar slot count)
        //   - Plugin context creation (PluginResourceContext + asset binding)
        //   - PluginFragmentHelper.registerPluginContext
        //   - GisPlugin.activate() + template registration
        //   - Static plugin-context handle (companion's `pluginContext`)
        //     getting populated.
        val loadResult = PluginTestSetup.loadPluginDirectly(pluginFile)
        check(loadResult.isSuccess) {
            "PluginManager.loadPlugin() failed for gis-plugin: " +
                "${loadResult.exceptionOrNull()?.javaClass?.simpleName}: " +
                loadResult.exceptionOrNull()?.message
        }

        // Confirm PluginFragmentHelper got the resource context — without
        // this, every fragment in the plugin will throw Resources$NotFound
        // on first inflation.
        val pluginCtx = com.itsaky.androidide.plugins.base.PluginFragmentHelper
            .getPluginContext("com.codeonthego.gisplugin")
        assertThat(pluginCtx).isNotNull()

        // Final logcat assertion — failures from any of the load steps
        // above (or from background coroutines kicked off by activate)
        // surface here.
        watcher.assertNoFatalPluginErrors()
    }
}
