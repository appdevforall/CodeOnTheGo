package com.itsaky.androidide.assets.addons

import com.itsaky.androidide.plugins.manager.core.PluginBuildFiles
import com.itsaky.androidide.plugins.manager.core.checkPluginId
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A bundled add-on after installation. [pluginId] is set for a plugin that reached its folder. A
 * plugin whose manifest names no valid ID has no [file] and carries a [failure] instead, with the
 * bundled file name in [sourceName]: it is reported to the user, and must not fail the setup.
 */
data class InstalledAddon(
	val kind: BundledAddonKind,
	val file: File?,
	val pluginId: String?,
	val failure: String? = null,
	val sourceName: String? = null,
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
			BundledAddonKind.PLUGIN -> installPlugin(fileName, input)
			BundledAddonKind.TEMPLATE -> {
				val file = AtomicFiles.writeInto(templatesDir, { input.copyToFile(it) }, { fileName })
				InstalledAddon(kind, file, null)
			}
		}

	private fun installPlugin(
		fileName: String,
		input: InputStream,
	): InstalledAddon {
		var pluginId: String? = null
		val file =
			try {
				AtomicFiles.writeInto(
					pluginsDir,
					{ input.copyToFile(it) },
					{ copy ->
						val id =
							runCatching { readPluginId(copy) }.getOrNull()
								?: throw RejectedPluginException("it has no readable manifest")
						checkPluginId(id).onFailure { throw RejectedPluginException(it.message ?: "invalid plugin ID") }
						pluginId = id
						"${PluginBuildFiles.fileStem(id, copy)}.$PLUGIN_ARCHIVE_EXTENSION"
					},
				)
			} catch (e: RejectedPluginException) {
				// writeInto has deleted the scratch copy. A write error (disk full) still propagates.
				return InstalledAddon(BundledAddonKind.PLUGIN, null, null, failure = e.message, sourceName = fileName)
			}
		return InstalledAddon(BundledAddonKind.PLUGIN, file, pluginId)
	}

	private class RejectedPluginException(
		message: String,
	) : IOException(message)

	private fun InputStream.copyToFile(dest: File) {
		dest.outputStream().use { out -> copyTo(out) }
	}
}
