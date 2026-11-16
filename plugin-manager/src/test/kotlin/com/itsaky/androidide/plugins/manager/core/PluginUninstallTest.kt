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
 * every update. The plugin uninstalled here is "a", which occurs in the other plugins' content
 * hashes, IDs and file names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginUninstallTest {
	private val pluginsDir by lazy { File(RuntimeEnvironment.getApplication().filesDir, "plugins") }
	private val oatDir by lazy { File(pluginsDir, "oat") }

	@After
	fun forgetManager() = forgetPluginManager()

	@Test
	fun anotherPluginsFileWhoseNameContainsTheIdIsKept() {
		val uninstalled = fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
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

	@Test
	fun anotherPluginsDataIsKept() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val others = listOf(file(pluginsDir, "a.extra/state.json"), file(pluginsDir, "org.demo.tool/cache.bin"))

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" deleted another plugin's data")
			.that(others.filterNot(File::exists))
			.isEmpty()
	}

	@Test
	fun anotherPluginsCompiledCodeIsKept() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val others =
			listOf(
				file(oatDir, "arm64/org.demo.tool-0123456789abcdef.odex"),
				file(oatDir, "arm64/a.extra-1111111111111111.vdex"),
				file(oatDir, "org.demo.tool-0123456789abcdef.cgp.cur.prof"),
			)

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" deleted another plugin's compiled code")
			.that(others.filterNot(File::exists))
			.isEmpty()
	}

	@Test
	fun thePluginsOwnDataAndCompiledCodeAreDeleted() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val own =
			listOf(
				file(pluginsDir, "a/state.json"),
				file(oatDir, "arm64/a-0123456789012345.odex"),
				file(oatDir, "arm64/a-0123456789012345.vdex"),
				file(oatDir, "arm64/a-0123456789012345.art"),
				file(oatDir, "a-0123456789012345.cgp.cur.prof"),
				file(oatDir, "a-0123456789012345.cgp.prof"),
				// From a file installed before hashes.
				file(oatDir, "arm64/a.odex"),
			)

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" left its own files").that(own.filter(File::exists)).isEmpty()
	}

	private fun file(
		dir: File,
		path: String,
	): File = File(dir, path).apply { parentFile!!.mkdirs() }.apply { writeText(path) }
}
