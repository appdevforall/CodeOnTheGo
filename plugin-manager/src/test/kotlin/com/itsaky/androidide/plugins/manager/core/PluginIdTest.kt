package com.itsaky.androidide.plugins.manager.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * A plugin ID names the plugin's files and folders, so the installer refuses one that is a path:
 * installing a plugin whose ID was `../files` deleted the app's whole files folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginIdTest {
	@get:Rule
	val temp = TemporaryFolder()

	@Test
	fun theInstallerRefusesAPluginIdThatIsAPath() {
		for (id in listOf("../files", "..", ".", "", "a/b", "/data", "a\\b", "a\u0000b")) {
			val result = pluginManager().getPluginValidation(plugin(id))

			assertWithMessage("the installer accepted \"$id\"").that(result.isFailure).isTrue()
			assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Invalid plugin ID \"$id\": it must be a name, not a path")
		}
	}

	@Test
	fun theInstallerAcceptsAPluginIdThatIsAName() {
		for (id in listOf("com.example.myplugin", "org.adfa.shell-hooks", "my_plugin", "a..b", "...")) {
			assertThat(
				pluginManager()
					.getPluginValidation(plugin(id))
					.getOrThrow()
					.manifest.id,
			).isEqualTo(id)
		}
	}

	@Test
	fun loadingRefusesAPluginIdThatIsAPath() {
		val files = RuntimeEnvironment.getApplication().filesDir
		File(files, "keep.txt").writeText("the app's")

		val result = pluginManager().loadPlugin(plugin("../files"))

		assertWithMessage("loading deleted the app's files").that(files.list()?.toList()).contains("keep.txt")
		assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Invalid plugin ID \"../files\": it must be a name, not a path")
	}

	@Test
	fun theInstallerRefusesTheIdsOfCoGosOwnFiles() {
		for ((id, message) in refusedCoGoIds) {
			val result = pluginManager().getPluginValidation(plugin(id))

			assertWithMessage("the installer accepted \"$id\"").that(result.isFailure).isTrue()
			assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Invalid plugin ID \"$id\": $message")
		}
	}

	@Test
	fun loadingRefusesTheIdsOfCoGosOwnFiles() {
		for ((id, message) in refusedCoGoIds) {
			val result = pluginManager().loadPlugin(plugin(id))

			assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Invalid plugin ID \"$id\": $message")
		}
	}

	@Test
	fun idsThatOnlyLookLikeCoGosOwnFilesAreAccepted() {
		for (id in listOf("oat.tools", "cgp", "my.cgp.tools", "oats", "x.cgpx")) {
			assertThat(
				pluginManager()
					.getPluginValidation(plugin(id))
					.getOrThrow()
					.manifest.id,
			).isEqualTo(id)
		}
	}

	@Test
	fun aManifestWithoutAnIdIsRefused() {
		assertThat(checkPluginId(null).exceptionOrNull())
			.hasMessageThat()
			.isEqualTo("Invalid plugin ID \"null\": it must be a name, not a path")
	}

	@After
	fun forgetManager() = forgetPluginManager()

	private val refusedCoGoIds =
		listOf(
			"oat" to "\"oat\" is the folder of compiled plugin code",
			"OAT" to "\"oat\" is the folder of compiled plugin code",
			"x.cgp" to "it must not end in .cgp, which names a plugin file",
			"x.CGP" to "it must not end in .cgp, which names a plugin file",
		)

	private fun plugin(id: String): File = fakePlugin(File(temp.newFolder(), "plugin.cgp"), id)
}
