package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Uninstalling a plugin deletes its own files only. Update uninstalls first, so the same goes for
 * every update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginUninstallTest {
	@After
	fun forgetManager() = forgetPluginManager()

	@Test
	fun anotherPluginsFileWhoseNameContainsTheIdIsKept() {
		val pluginsDir = File(RuntimeEnvironment.getApplication().filesDir, "plugins")
		val uninstalled = fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		// "a" occurs in the content hash of the first one and in the ID of the second.
		val others =
			listOf(
				fakePlugin(File(pluginsDir, "org.demo.tool-0123456789abcdef.cgp"), "org.demo.tool"),
				fakePlugin(File(pluginsDir, "a.extra-1111111111111111.cgp"), "a.extra"),
			)

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertThat(uninstalled.exists()).isFalse()
		assertWithMessage("uninstalling \"a\" deleted another plugin's file")
			.that(pluginsDir.listFiles()!!.toList())
			.containsAtLeastElementsIn(others)
	}
}
