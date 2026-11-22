package com.itsaky.androidide.assets.addons

import com.itsaky.androidide.plugins.manager.core.PluginBuildFiles
import com.itsaky.androidide.plugins.manager.core.checkPluginId
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * A bundled add-on after installation. [pluginId] is set for a plugin that reached its folder. A
 * plugin whose manifest names no valid ID, or an add-on whose content differs from the bundle
 * index, has no [file] and carries a [failure] instead, with the bundled file name in
 * [sourceName]: it is reported to the user, and must not fail the setup.
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
	/**
	 * Copies [input] into place. With [sha256] (hex, from the bundle index), a copy whose content
	 * differs is rejected like an unreadable plugin: reported, not thrown, and nothing replaced.
	 */
	fun install(
		kind: BundledAddonKind,
		fileName: String,
		input: InputStream,
		sha256: String? = null,
	): InstalledAddon {
		val digest = sha256?.let { MessageDigest.getInstance("SHA-256") }
		val source = digest?.let { DigestInputStream(input, it) } ?: input
		val checkContent = {
			if (digest != null && digest.digest().toHex() != sha256) {
				throw RejectedAddonException("its content does not match the bundle index")
			}
		}
		return when (kind) {
			BundledAddonKind.PLUGIN -> {
				installPlugin(fileName, source, checkContent)
			}

			BundledAddonKind.TEMPLATE -> {
				try {
					val file =
						AtomicFiles.writeInto(templatesDir, { source.copyToFile(it) }, {
							checkContent()
							fileName
						})
					InstalledAddon(kind, file, null)
				} catch (e: RejectedAddonException) {
					InstalledAddon(kind, null, null, failure = e.message, sourceName = fileName)
				}
			}
		}
	}

	private fun installPlugin(
		fileName: String,
		input: InputStream,
		checkContent: () -> Unit,
	): InstalledAddon {
		var pluginId: String? = null
		val file =
			try {
				AtomicFiles.writeInto(
					pluginsDir,
					{ input.copyToFile(it) },
					{ copy ->
						checkContent()
						val id =
							try {
								readPluginId(copy)
							} catch (e: Exception) {
								null
							} ?: throw RejectedAddonException("it has no readable manifest")
						checkPluginId(id).onFailure { throw RejectedAddonException(it.message ?: "invalid plugin ID") }
						val name = "${PluginBuildFiles.fileStem(id, copy)}.$PLUGIN_ARCHIVE_EXTENSION"
						// 255 bytes is the longest file name the file system takes.
						if (name.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES) {
							throw RejectedAddonException("its plugin ID is too long")
						}
						pluginId = id
						name
					},
				)
			} catch (e: RejectedAddonException) {
				// writeInto has deleted the scratch copy. A write error (disk full) still propagates.
				return InstalledAddon(BundledAddonKind.PLUGIN, null, null, failure = e.message, sourceName = fileName)
			}
		return InstalledAddon(BundledAddonKind.PLUGIN, file, pluginId)
	}

	private companion object {
		const val MAX_FILE_NAME_BYTES = 255
	}

	private class RejectedAddonException(
		message: String,
	) : IOException(message)

	private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

	private fun InputStream.copyToFile(dest: File) {
		dest.outputStream().use { out -> copyTo(out) }
	}
}
