package com.itsaky.androidide.plugins.manager.loaders

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A plugin with its own resource package id must leave the activity's Resources alone: adding its
 * loader there makes Android 11+ rebuild them during the tab's first inflation, and the views built
 * then drew sp text at about half size (ADFA-7142). Robolectric does not reproduce the size error,
 * so this pins the mutation and that sp text through the plugin keeps matching the activity's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
class PluginResourceContextActivityResourcesTest {
	private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

	// Robolectric 4.11 cannot load an APK through ResourcesProvider on R+, so an empty loader
	// stands in for the one built from the plugin's APK.
	private val pluginLoader = ResourcesLoader()

	private val pluginContext =
		PluginResourceContext(
			activity.applicationContext,
			PLUGIN_ID,
			activity.applicationContext.resources,
			PackageInfo().apply {
				packageName = PLUGIN_ID
				applicationInfo = ApplicationInfo().apply { sourceDir = "plugin.apk" }
			},
			javaClass.classLoader,
		).also {
			PluginResourceContext::class.java
				.getDeclaredField("cachedLoader")
				.apply { isAccessible = true }
				.set(it, pluginLoader)
		}

	private lateinit var pluginInflater: LayoutInflater

	@Before
	fun registerPlugin() {
		assertThat(pluginContext.usesCustomPackageId()).isTrue()
		PluginFragmentHelper.registerPluginContext(PLUGIN_ID, pluginContext)
		pluginInflater = PluginFragmentHelper.getPluginInflater(PLUGIN_ID, LayoutInflater.from(activity))
	}

	@After
	fun unregisterPlugin() {
		PluginFragmentHelper.unregisterPluginContext(PLUGIN_ID)
	}

	@Test
	fun theFirstInflationAddsThePluginsLoaderToACopyNotToTheActivity() {
		val activityAssets = activity.resources.assets

		val view = pluginInflater.inflate(android.R.layout.simple_list_item_1, null, false)

		assertThat(view).isInstanceOf(TextView::class.java)
		assertThat(loadersOf(activity.resources)).isEmpty()
		assertThat(activity.resources.assets).isSameInstanceAs(activityAssets)
		assertThat(loadersOf(pluginInflater.context.resources)).containsExactly(pluginLoader)
	}

	@Test
	fun spTextThroughThePluginMatchesTheActivity() {
		assertThat(spTextSize(pluginInflater.context)).isEqualTo(spTextSize(activity))
	}

	@Test
	fun spTextThroughThePluginFollowsAFontScaleChangeTheActivityHandlesItself() {
		val before = spTextSize(activity)
		assertThat(spTextSize(pluginInflater.context)).isEqualTo(before)

		// EditorActivityKt declares fontScale in configChanges, so its Resources object is kept and
		// updated in place rather than replaced.
		@Suppress("DEPRECATION")
		activity.resources.updateConfiguration(
			Configuration(activity.resources.configuration).apply { fontScale = 2f },
			activity.resources.displayMetrics,
		)

		assertThat(spTextSize(activity)).isGreaterThan(before)
		assertThat(spTextSize(pluginInflater.context)).isEqualTo(spTextSize(activity))
	}

	private fun spTextSize(context: Context): Float = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }.textSize

	/** Resources.getLoaders() is hidden; Robolectric's framework jar still has it. */
	private fun loadersOf(resources: Resources): List<*> = Resources::class.java.getMethod("getLoaders").invoke(resources) as List<*>

	private companion object {
		const val PLUGIN_ID = "com.itsaky.androidide.plugins.manager.loaders.fakeplugin"
	}
}
