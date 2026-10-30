package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A plugin with its own resource package id resolves its resources through the activity's, which
 * every such plugin adds its APK to. Asset paths carry no package id, so a path two plugins both
 * ship must still open the reading plugin's own copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginResourceContextAssetsTest {
	private val app: Context = RuntimeEnvironment.getApplication()

	/** Stands in for the Resources the loader builds from the plugin's own APK. */
	@Suppress("DEPRECATION")
	private val ownResources =
		Resources(
			AssetManager::class.java.getDeclaredConstructor().newInstance(),
			app.resources.displayMetrics,
			app.resources.configuration,
		)

	private val pluginContext =
		PluginResourceContext(
			app,
			PLUGIN_ID,
			ownResources,
			PackageInfo().apply {
				packageName = PLUGIN_ID
				applicationInfo = ApplicationInfo()
			},
			javaClass.classLoader,
		)

	@Before
	fun registerPlugin() {
		assertThat(pluginContext.usesCustomPackageId()).isTrue()
		PluginFragmentHelper.registerPluginContext(PLUGIN_ID, pluginContext)
		PluginFragmentHelper.getPluginInflater(PLUGIN_ID, LayoutInflater.from(app))
	}

	@After
	fun unregisterPlugin() {
		PluginFragmentHelper.unregisterPluginContext(PLUGIN_ID)
	}

	@Test
	fun assetsComeFromThePluginsOwnApkWhileResourcesComeFromTheActivity() {
		assertThat(pluginContext.resources).isSameInstanceAs(app.resources)
		assertThat(pluginContext.assets).isSameInstanceAs(ownResources.assets)
	}

	private companion object {
		const val PLUGIN_ID = "com.itsaky.androidide.plugins.manager.loaders.fakeplugin"
	}
}
