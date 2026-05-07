/*
 * Plugin install path for instrumented tests.
 *
 * The host IDE loads plugins from `<context.filesDir>/plugins/[name].cgp` at app
 * startup (see PluginManager.loadPlugins). For instrumented tests we need
 * to stage a plugin into that directory before the host's plugin manager
 * reads it.
 *
 * Two ways to provide the .cgp:
 *
 *  1. Bundled androidTest asset.
 *     Build the plugin's debug `.cgp` (e.g.
 *     `./gradlew :gis-plugin:assemblePluginDebug`) and copy
 *     `gis-plugin/build/plugin/gis-plugin-debug.cgp` into
 *     `app/src/androidTest/assets/plugins/<name>.cgp`. Test code then
 *     calls `installFromAndroidTestAssets(...)`.
 *
 *  2. Pre-stage via adb push.
 *     CI can `adb push` the .cgp to
 *     `/data/data/<host.applicationId>/files/plugins/` before the test run.
 *     Test code then calls `installFromFilesystem(...)` after verifying the
 *     file exists.
 *
 * Recommended: option 1 for stability (asset lives with the test). Either
 * way, after the .cgp is in place call `loadPluginDirectly(...)` so the
 * host picks it up without a full relaunch.
 */
package com.itsaky.androidide.plugins.testsupport

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.plugins.manager.core.PluginManager
import kotlinx.coroutines.runBlocking
import java.io.File

object PluginTestSetup {

    /** Where the host expects plugin .cgp files. Mirrors PluginManager.pluginsDir. */
    fun pluginsDir(context: Context): File =
        File(context.filesDir, "plugins").apply { mkdirs() }

    /**
     * Copy a `.cgp` from the test APK's assets into the host's plugin dir.
     *
     * @param assetPath path under `androidTest/assets/`, e.g. `plugins/gis-plugin-debug.cgp`
     * @param destFileName name to use inside `<filesDir>/plugins/`. Must end `.cgp`.
     */
    fun installFromAndroidTestAssets(
        targetContext: Context,
        assetPath: String,
        destFileName: String,
    ): File {
        require(destFileName.endsWith(".cgp", ignoreCase = true)) {
            "destFileName must end with .cgp; got '$destFileName'"
        }
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val dest = File(pluginsDir(targetContext), destFileName)
        testCtx.assets.open(assetPath).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return dest
    }

    /**
     * Stage a `.cgp` from a known filesystem path (e.g. `adb push`-ed by CI).
     */
    fun installFromFilesystem(
        targetContext: Context,
        sourceFile: File,
        destFileName: String,
    ): File {
        require(sourceFile.exists() && sourceFile.canRead()) {
            "Source plugin file missing or unreadable: ${sourceFile.absolutePath}"
        }
        require(destFileName.endsWith(".cgp", ignoreCase = true)) {
            "destFileName must end with .cgp; got '$destFileName'"
        }
        val dest = File(pluginsDir(targetContext), destFileName)
        sourceFile.copyTo(dest, overwrite = true)
        return dest
    }

    /**
     * Trigger the existing PluginManager singleton to load a single plugin
     * file. Returns the load Result so callers can assert success and
     * inspect failures.
     *
     * Prefer this over [reloadAllPlugins] in tests — it's deterministic
     * and doesn't depend on whatever else happens to be in the plugins
     * directory.
     */
    fun loadPluginDirectly(pluginFile: File): Result<*> {
        val manager = PluginManager.getInstance()
            ?: error(
                "PluginManager not initialized. The host application must " +
                    "have run its plugin-system bootstrap before this test executes. " +
                    "If running this test in isolation, launch SplashActivity or " +
                    "MainActivity first to trigger CredentialProtectedApplicationLoader."
            )
        return manager.loadPlugin(pluginFile)
    }

    /**
     * Reload all plugins under `<filesDir>/plugins/`. Useful when the test
     * has dropped multiple .cgp files and wants the host to pick them all
     * up at once.
     */
    fun reloadAllPlugins() {
        val manager = PluginManager.getInstance()
            ?: error("PluginManager not initialized")
        runBlocking { manager.loadPlugins() }
    }

    /**
     * Remove all plugin .cgp files from the host's plugins directory.
     * Useful in `@After` to keep the device state clean for the next test
     * run, especially when running tests on a physical device or persistent
     * Firebase Test Lab matrix entry.
     */
    fun clearPluginsDir(targetContext: Context) {
        pluginsDir(targetContext).listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".cgp", ignoreCase = true)) {
                f.delete()
            }
        }
    }
}
