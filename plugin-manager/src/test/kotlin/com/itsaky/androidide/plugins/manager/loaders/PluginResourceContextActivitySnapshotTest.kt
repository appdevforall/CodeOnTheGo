package com.itsaky.androidide.plugins.manager.loaders

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginResourceContextActivitySnapshotTest {
	private val app: Context = RuntimeEnvironment.getApplication()

	private val pluginContext =
		PluginResourceContext(
			app,
			PLUGIN_ID,
			app.resources,
			PackageInfo().apply {
				packageName = "com.itsaky.androidide.plugins.manager.loaders.fakeplugin"
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
	fun anInflaterInThePluginContextDoesNotReplaceTheActivitySnapshot() {
		PluginFragmentHelper.getPluginInflater(PLUGIN_ID, LayoutInflater.from(pluginContext))

		assertThat(PluginFragmentHelper.getCurrentActivityContext(PLUGIN_ID)).isSameInstanceAs(app)
		assertThat(pluginContext.resources).isSameInstanceAs(app.resources)
	}

	@Test
	fun anInflaterOverAWrapperOfThePluginContextDoesNotReplaceTheActivitySnapshot() {
		val wrapped = LayoutInflater.from(pluginContext).cloneInContext(ContextWrapper(pluginContext))
		PluginFragmentHelper.getPluginInflater(PLUGIN_ID, wrapped)

		assertThat(PluginFragmentHelper.getCurrentActivityContext(PLUGIN_ID)).isSameInstanceAs(app)
		assertThat(pluginContext.resources).isSameInstanceAs(app.resources)
	}

	private companion object {
		const val PLUGIN_ID = "com.itsaky.androidide.plugins.manager.loaders.fakeplugin"
	}
}
