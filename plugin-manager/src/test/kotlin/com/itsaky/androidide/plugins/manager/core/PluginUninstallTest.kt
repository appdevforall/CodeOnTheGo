package com.itsaky.androidide.plugins.manager.core

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

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

	@Test
	fun theProfilesOfAPluginInstalledWithAnUpperCaseExtensionAreDeleted() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.CGP"), "a")
		val own = listOf(file(oatDir, "a-0123456789012345.CGP.prof"), file(oatDir, "a-0123456789012345.CGP.cur.prof"))

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" left the profiles of its .CGP file").that(own.filter(File::exists)).isEmpty()
	}

	@Test
	fun compiledCodeOfAnotherPluginWhoseIdLooksLikeThisPluginsFileNameIsKept() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		// Installed by an older CoGo build: named by its ID, which looks like "a" plus a hash.
		fakePlugin(File(pluginsDir, "a-0123456789abcdef.cgp"), "a-0123456789abcdef")
		val others =
			listOf(
				file(oatDir, "arm64/a-0123456789abcdef.odex"),
				file(oatDir, "a-0123456789abcdef.cgp.cur.prof"),
			)

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" deleted the compiled code of \"a-0123456789abcdef\"")
			.that(others.filterNot(File::exists))
			.isEmpty()
	}

	@Test
	fun compiledCodeOfAnotherPluginInstalledWithAnUpperCaseExtensionIsKept() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		fakePlugin(File(pluginsDir, "a-0123456789abcdef.CGP"), "a-0123456789abcdef")
		val other = file(oatDir, "arm64/a-0123456789abcdef.odex")

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" deleted the compiled code of a .CGP file").that(other.exists()).isTrue()
	}

	@Test
	fun aLinkAtThePluginsDataFolderIsDeletedNotFollowed() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val victim = victim()
		val link = File(pluginsDir, "a").toPath()
		Files.createSymbolicLink(link, victim.toPath())

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("the link's target lost files").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertWithMessage("the link is still there").that(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse()
	}

	@Test
	fun aDanglingLinkAtThePluginsDataFolderIsDeleted() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val link = File(pluginsDir, "a").toPath()
		Files.createSymbolicLink(link, File(RuntimeEnvironment.getApplication().filesDir.parentFile, "gone").toPath())

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("the dangling link survived").that(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse()
	}

	@Test
	fun aLinkAtThePluginsIconsFolderIsDeletedNotFollowed() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val victim = victim()
		val link = iconsDir("a").toPath()
		Files.createSymbolicLink(link, victim.toPath())

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("the link's target lost files").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertWithMessage("the link is still there").that(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse()
	}

	@Test
	fun aDanglingLinkAtThePluginsIconsFolderIsDeleted() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val link = iconsDir("a").toPath()
		Files.createSymbolicLink(link, File(RuntimeEnvironment.getApplication().filesDir.parentFile, "gone").toPath())

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("the dangling link survived").that(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse()
	}

	@Test
	fun aLinkInThePluginsDataIsNotFollowed() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val victim = victim()
		File(pluginsDir, "a").mkdirs()
		Files.createSymbolicLink(File(pluginsDir, "a/link").toPath(), victim.toPath())

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" emptied a link's target").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertThat(File(pluginsDir, "a").exists()).isFalse()
	}

	@Test
	fun aLinkInThePluginsIconsIsNotFollowed() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val victim = victim()
		val icons = File(RuntimeEnvironment.getApplication().getDir("plugin_icons", Context.MODE_PRIVATE), "a").apply { mkdirs() }
		Files.createSymbolicLink(File(icons, "link").toPath(), victim.toPath())

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" emptied a link's target").that(victim.list()?.toList()).containsExactly("keep.txt")
		assertThat(icons.exists()).isFalse()
	}

	@Test
	fun aLinkInTheCompiledCodeFolderIsNotFollowed() {
		fakePlugin(File(pluginsDir, "a-0123456789012345.cgp"), "a")
		val victim = victim().also { File(it, "a-0123456789012345.odex").writeText("not a plugin's") }
		oatDir.mkdirs()
		Files.createSymbolicLink(File(oatDir, "link").toPath(), victim.toPath())

		assertThat(pluginManager().uninstallPlugin("a")).isTrue()

		assertWithMessage("uninstalling \"a\" deleted a file through a link")
			.that(victim.list()?.toList())
			.containsExactly("keep.txt", "a-0123456789012345.odex")
	}

	private fun iconsDir(id: String): File = File(RuntimeEnvironment.getApplication().getDir("plugin_icons", Context.MODE_PRIVATE), id)

	// Outside the folders an uninstall deletes, with a file that must survive.
	private fun victim(): File =
		File(RuntimeEnvironment.getApplication().filesDir.parentFile, "victim").apply { mkdirs() }.also {
			File(it, "keep.txt").writeText("not a plugin's")
		}

	private fun file(
		dir: File,
		path: String,
	): File = File(dir, path).apply { parentFile!!.mkdirs() }.apply { writeText(path) }
}
