package com.itsaky.androidide.plugins.manager.core

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.plugins.PluginLogger
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
			val result = manager().getPluginValidation(plugin(id))

			assertWithMessage("the installer accepted \"$id\"").that(result.isFailure).isTrue()
			assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Invalid plugin ID \"$id\": it must be a name, not a path")
		}
	}

	@Test
	fun theInstallerAcceptsAPluginIdThatIsAName() {
		for (id in listOf("com.example.myplugin", "org.adfa.shell-hooks", "my_plugin", "a..b", "...")) {
			assertThat(
				manager()
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

		val result = manager().loadPlugin(plugin("../files"))

		assertWithMessage("loading deleted the app's files").that(files.list()?.toList()).contains("keep.txt")
		assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Invalid plugin ID \"../files\": it must be a name, not a path")
	}

	@Test
	fun aManifestWithoutAnIdIsRefused() {
		assertThat(checkPluginId(null).exceptionOrNull())
			.hasMessageThat()
			.isEqualTo("Invalid plugin ID \"null\": it must be a name, not a path")
	}

	// The manager is a singleton, and each test has an application of its own.
	@After
	fun forgetManager() {
		PluginManager::class.java
			.getDeclaredField("INSTANCE")
			.apply { isAccessible = true }
			.set(null, null)
	}

	private fun manager() = PluginManager.getInstance(RuntimeEnvironment.getApplication(), Any(), NoLogger)

	// A plugin file whose AndroidManifest states [id]: Robolectric can't read one from an APK.
	private fun plugin(id: String): File {
		val file = File(temp.newFolder(), "plugin.cgp")
		ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("classes.dex")) }
		val info =
			PackageInfo().apply {
				packageName = "org.example.plugin"
				applicationInfo =
					ApplicationInfo().apply {
						metaData =
							Bundle().apply {
								putString("plugin.id", id)
								putString("plugin.main_class", "org.example.plugin.Main")
								// Loading deletes and recreates the plugin's icon folder, named after its ID.
								putString("plugin.icon_day", "icon.png")
							}
					}
			}
		shadowOf(RuntimeEnvironment.getApplication().packageManager).setPackageArchiveInfo(file.absolutePath, info)
		return file
	}

	private object NoLogger : PluginLogger {
		override val pluginId = "test"

		override fun debug(message: String) = Unit

		override fun debug(
			message: String,
			error: Throwable,
		) = Unit

		override fun info(message: String) = Unit

		override fun info(
			message: String,
			error: Throwable,
		) = Unit

		override fun warn(message: String) = Unit

		override fun warn(
			message: String,
			error: Throwable,
		) = Unit

		override fun error(message: String) = Unit

		override fun error(
			message: String,
			error: Throwable,
		) = Unit
	}
}
