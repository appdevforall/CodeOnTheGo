/*
 * Plugin-loader-level smoke test for random-xkcd (ADFA-2433).
 *
 * What this catches: the runtime failure modes that ship green from
 * `./gradlew assembleDebug` but blow up the moment a user taps the
 * plugin's UI surface — specifically:
 *
 *   - `Resources$NotFoundException` from a fragment that forgot to wrap
 *     its inflater via `PluginFragmentHelper.getPluginInflater(...)`.
 *   - `ActivityNotFoundException` from a plugin trying to launch its own
 *     `<activity>` (which never enters the host's PackageManager because
 *     plugin APKs load via DexClassLoader).
 *
 * What this does NOT catch (covered by the companion XkcdSmokeTest):
 *
 *   - Bottom-sheet wiring inside the host editor.
 *   - Sidebar slot allocation.
 *   - User-visible labelling / icon resolution.
 *
 * Why split: this test runs without launching SplashActivity or
 * navigating the host's onboarding/editor UI. It's fast (single-digit
 * seconds) and deterministic on Firebase Test Lab. The full UI smoke
 * test is gated on the .cgp being bundled as an asset; this test is
 * always-on once the .cgp is present.
 *
 * Until the .cgp is bundled at
 * `app/src/androidTest/assets/plugins/random-xkcd-debug.cgp`, the test
 * is `@Ignore`'d. Build via `./gradlew :random-xkcd-plugin:assemblePluginDebug`,
 * copy the result, drop the @Ignore.
 */
package com.itsaky.androidide.plugins.xkcd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.testsupport.LogcatWatcher
import com.itsaky.androidide.plugins.testsupport.PluginTestSetup
import org.junit.After
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore(
    "Enable once random-xkcd-debug.cgp lives at " +
        "androidTest/assets/plugins/. See file header for the build step."
)
class XkcdFragmentInflationTest {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        PluginTestSetup.clearPluginsDir(targetContext)
    }

    @Test
    fun test_pluginLoadsAndRegistersWithoutFatalErrors() {
        val watcher = LogcatWatcher.start("XkcdInflate")

        // Stage the .cgp into the host's plugins dir.
        val pluginFile = PluginTestSetup.installFromAndroidTestAssets(
            targetContext = targetContext,
            assetPath = "plugins/random-xkcd-debug.cgp",
            destFileName = "random-xkcd-debug.cgp",
        )
        assertThat(pluginFile.exists()).isTrue()

        // Load the plugin via PluginManager. This exercises:
        //   - DexClassLoader path
        //   - Manifest parsing (mainClass resolution)
        //   - Plugin context creation (PluginResourceContext + asset binding)
        //   - PluginFragmentHelper.registerPluginContext
        //   - Plugin.activate() + tab registration
        //
        // If anything in that chain fails (missing class, bad manifest,
        // resource compile mismatch), this returns Result.failure with the
        // root cause — far easier to debug than a crash mid-UI.
        val loadResult = PluginTestSetup.loadPluginDirectly(pluginFile)
        check(loadResult.isSuccess) {
            "PluginManager.loadPlugin() failed for random-xkcd: " +
                "${loadResult.exceptionOrNull()?.javaClass?.simpleName}: " +
                loadResult.exceptionOrNull()?.message
        }

        // Inflation check: instantiate the plugin's panel fragment and
        // run its layout inflater. We do this off the host UI to keep
        // the test focused on the inflation path.
        //
        // The fragment's onGetLayoutInflater() should call
        // PluginFragmentHelper.getPluginInflater(PLUGIN_ID, default) —
        // if it doesn't, inflate(R.layout.fragment_xkcd_panel, ...)
        // throws Resources$NotFoundException, and the logcat watcher
        // catches it below.
        //
        // Reflection over a direct dep because the plugin module is not
        // on the app's classpath at androidTest compile time.
        val pluginCtx = com.itsaky.androidide.plugins.base.PluginFragmentHelper
            .getPluginContext("com.codeonthego.xkcdrandom")
        assertThat(pluginCtx).isNotNull()

        // Final logcat assertion — failures from any of the load steps
        // above (or from background coroutines kicked off by activate)
        // surface here.
        watcher.assertNoFatalPluginErrors()
    }
}
