package com.itsaky.androidide.assets.addons

import com.itsaky.androidide.plugins.manager.core.PluginBuildFiles
import com.itsaky.androidide.plugins.manager.core.checkPluginId
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import java.io.File
import java.io.IOException
import java.io.InputStream

/** An add-on file that reached its folder. [pluginId] is set for a plugin. */
data class InstalledAddon(
	val kind: BundledAddonKind,
	val file: File,
	val pluginId: String?,
)

/**
 * Copies bundled add-on files into place. A plugin lands in [pluginsDir] as
 * `<pluginId>-<hash>.cgp`, the name an install from the Add-ons Manager gives it, and a template in
 * [templatesDir] under its bundled name.
 *
 * The plugin ID is only in the plugin's manifest, so the name is chosen on the device, from the
 * copied file ([readPluginId]), and not at build time. The bundle then needs no manifest reader in
 * Gradle, and the naming rule stays in [PluginBuildFiles.fileStem].
 */
class BundledAddonInstaller(
	private val pluginsDir: File,
	private val templatesDir: File,
	private val readPluginId: (File) -> String?,
) {
	fun install(
		kind: BundledAddonKind,
		fileName: String,
		input: InputStream,
	): InstalledAddon =
		when (kind) {
			BundledAddonKind.PLUGIN -> installPlugin(input)
			BundledAddonKind.TEMPLATE -> {
				val file = AtomicFiles.writeInto(templatesDir, { input.copyToFile(it) }, { fileName })
				InstalledAddon(kind, file, null)
			}
		}

	private fun installPlugin(input: InputStream): InstalledAddon {
		var pluginId: String? = null
		val file =
			AtomicFiles.writeInto(
				pluginsDir,
				{ input.copyToFile(it) },
				{ copy ->
					val id = readPluginId(copy) ?: throw IOException("Bundled plugin has no readable manifest")
					checkPluginId(id).getOrThrow()
					pluginId = id
					"${PluginBuildFiles.fileStem(id, copy)}.$PLUGIN_ARCHIVE_EXTENSION"
				},
			)
		return InstalledAddon(BundledAddonKind.PLUGIN, file, pluginId)
	}

	private fun InputStream.copyToFile(dest: File) {
		dest.outputStream().use { out -> copyTo(out) }
	}
}
