package com.itsaky.androidide.plugins.manager.loaders

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.util.TypedValue
import android.view.ContextThemeWrapper
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
 * A plugin with its own resource package id inflates its views against a copy of the activity's
 * Resources, which adding its loader never rebuilds mid-inflation (ADFA-7142). The activity's own
 * Resources carry the loader too, because plugins also resolve their ids through the activity:
 * AI Core's ChatFragment calls Fragment.getString and builds dialogs on
 * ContextThemeWrapper(requireContext(), R.style.PluginTheme).
 *
 * Robolectric does not reproduce the half-size text and cannot load an APK into a ResourcesLoader,
 * so these pin where the loader goes, not that a plugin id resolves. It also has no shadow for the
 * native theme rebase that adding a loader to the activity triggers: any theme made on the
 * activity's Resources before that, the activity's own included, can then neither build views nor
 * be copied. So sp sizes are resolved through each side's DisplayMetrics, and the one inflation
 * test snapshots a host theme made after the patch.
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
	fun onceThePluginContextIsUsedItsLoaderIsReachableThroughTheActivity() {
		pluginInflater.context.resources

		// AI Core: Fragment.getString resolves through requireContext(), and its dialogs through
		// ContextThemeWrapper(requireContext(), R.style.PluginTheme).
		assertThat(loadersOf(activity.resources)).contains(pluginLoader)
		assertThat(loadersOf(ContextThemeWrapper(activity, android.R.style.Theme_Material).resources))
			.contains(pluginLoader)
	}

	@Test
	fun thePluginContextHandsOutACopyOfTheActivitysResourcesThatCarriesTheLoader() {
		val resources = pluginInflater.context.resources

		assertThat(resources).isNotSameInstanceAs(activity.resources)
		assertThat(loadersOf(resources)).contains(pluginLoader)
	}

	@Test
	fun pluginViewsAndThemeAreBuiltOnTheCopy() {
		pluginInflater.context.resources
		val host = ContextThemeWrapper(activity, android.R.style.Theme_Material)
		val inflater = PluginFragmentHelper.getPluginInflater(PLUGIN_ID, LayoutInflater.from(host))

		val view = inflater.inflate(android.R.layout.simple_list_item_1, null, false)

		assertThat(view).isInstanceOf(TextView::class.java)
		assertThat(view.resources).isNotSameInstanceAs(activity.resources)
		assertThat(view.resources).isSameInstanceAs(inflater.context.resources)
		assertThat(inflater.context.theme.resources).isSameInstanceAs(view.resources)
	}

	@Test
	fun spThroughThePluginMatchesTheActivity() {
		assertThat(sp15(pluginInflater.context.resources)).isEqualTo(sp15(activity.resources))
	}

	@Test
	fun spThroughThePluginFollowsAFontScaleChangeTheActivityHandlesItself() {
		val before = sp15(pluginInflater.context.resources)
		assertThat(before).isEqualTo(sp15(activity.resources))

		// EditorActivityKt declares fontScale in configChanges, so its Resources object is kept and
		// updated in place rather than replaced.
		@Suppress("DEPRECATION")
		activity.resources.updateConfiguration(
			Configuration(activity.resources.configuration).apply { fontScale = 2f },
			activity.resources.displayMetrics,
		)

		assertThat(sp15(activity.resources)).isGreaterThan(before)
		assertThat(sp15(pluginInflater.context.resources)).isEqualTo(sp15(activity.resources))
	}

	private fun sp15(resources: Resources): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)

	/** Resources.getLoaders() is hidden; Robolectric's framework jar still has it. */
	private fun loadersOf(resources: Resources): List<*> = Resources::class.java.getMethod("getLoaders").invoke(resources) as List<*>

	private companion object {
		const val PLUGIN_ID = "com.itsaky.androidide.plugins.manager.loaders.fakeplugin"
	}
}
