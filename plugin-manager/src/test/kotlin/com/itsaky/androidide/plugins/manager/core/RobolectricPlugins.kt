package com.itsaky.androidide.plugins.manager.core

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The plugin manager of the test's application. */
internal fun pluginManager() = PluginManager.getInstance(RuntimeEnvironment.getApplication(), Any(), NoLogger)

/** Forgets the singleton plugin manager: each test has an application of its own. */
internal fun forgetPluginManager() {
	PluginManager::class.java
		.getDeclaredField("INSTANCE")
		.apply { isAccessible = true }
		.set(null, null)
}

/** Writes [file] as a plugin whose AndroidManifest states [id] and [mainClass]: Robolectric can't read one from an APK. */
internal fun fakePlugin(
	file: File,
	id: String,
	mainClass: String = "org.example.plugin.Main",
): File {
	file.parentFile?.mkdirs()
	ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("classes.dex")) }
	val info =
		PackageInfo().apply {
			packageName = "org.example.plugin"
			applicationInfo =
				ApplicationInfo().apply {
					metaData =
						Bundle().apply {
							putString("plugin.id", id)
							putString("plugin.main_class", mainClass)
							// Loading deletes and recreates the plugin's icon folder, named after its ID.
							putString("plugin.icon_day", "icon.png")
						}
				}
		}
	shadowOf(RuntimeEnvironment.getApplication().packageManager).setPackageArchiveInfo(file.absolutePath, info)
	return file
}

/** A plugin that keeps its context. Name it as a fakePlugin's main class: the class loader finds it through its parent. */
class NoopPlugin : IPlugin {
	lateinit var context: PluginContext

	override fun initialize(context: PluginContext): Boolean {
		this.context = context
		return true
	}

	override fun activate() = true

	override fun deactivate() = true

	override fun dispose() = Unit
}

internal object NoLogger : PluginLogger {
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
