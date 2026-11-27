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
 *
 * A plugin whose ID already has another build in [pluginsDir] replaces it only when every such build
 * is signed with the same key, the rule the Add-ons Manager applies to an install over a plugin, or
 * when both are unsigned, as the plugins the addons repository builds are: there is no key to
 * protect, and refusing would stop every update of them (ADFA-7345 brings the Add-ons Manager in
 * line). Otherwise the bundled plugin is rejected and reported, and the installed build stays.
 *
 * @param signatureOf the signing certificate hash of a plugin file, or null if it has none (or it
 *   can't be read).
 * @param bundledBefore the plugin IDs an earlier bundle installed. Their builds came from an earlier
 *   APK of this same app, as trusted as this one, so they are replaced without a signature check.
 * @param removeOtherBuilds removes the installed builds of a plugin ID, called once replacing them is
 *   allowed. A sync passes an uninstall that also clears the plugin's state.
 */
class BundledAddonInstaller(
	private val pluginsDir: File,
	private val templatesDir: File,
	private val readPluginId: (File) -> String?,
	private val signatureOf: (File) -> ByteArray?,
	private val bundledBefore: Set<String> = emptySet(),
	private val removeOtherBuilds: (pluginId: String, builds: List<File>) -> Unit = { _, builds -> builds.forEach(File::delete) },
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
							} ?: throw RejectedAddonException(UNREADABLE_MANIFEST)
						checkPluginId(id).onFailure { throw RejectedAddonException(it.message ?: "invalid plugin ID") }
						val name = "${PluginBuildFiles.fileStem(id, copy)}.$PLUGIN_ARCHIVE_EXTENSION"
						// 255 bytes is the longest file name the file system takes.
						if (name.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES) {
							throw RejectedAddonException("its plugin ID is too long")
						}
						replaceOtherBuilds(id, name, copy)
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

	/**
	 * Before [copy] of [pluginId] lands as [fileName]: removes the other installed builds of that ID if
	 * replacing them is allowed (see the class), and rejects [copy] otherwise. A build of the same name
	 * is the same bytes and is overwritten in place. Throws an I/O error when a build stays after
	 * removal, so the two never load together.
	 */
	private fun replaceOtherBuilds(
		pluginId: String,
		fileName: String,
		copy: File,
	) {
		val others = otherBuilds(pluginId, fileName)
		if (others.isEmpty()) return
		if (pluginId !in bundledBefore) {
			val incoming = signatureOf(copy)
			others.forEach { installed -> signatureVerdict(incoming, signatureOf(installed))?.let { throw RejectedAddonException(it) } }
		}
		removeOtherBuilds(pluginId, others)
		val left = otherBuilds(pluginId, fileName)
		if (left.isNotEmpty()) {
			throw IOException("Could not remove the installed build of $pluginId: ${left.joinToString { it.name }}")
		}
	}

	/** Null when a build signed [installed] may be replaced by one signed [incoming]; else why not. */
	private fun signatureVerdict(
		incoming: ByteArray?,
		installed: ByteArray?,
	): String? =
		when {
			incoming == null && installed == null -> null
			incoming == null || installed == null -> ONE_UNSIGNED
			incoming.contentEquals(installed) -> null
			else -> SIGNED_BY_ANOTHER_KEY
		}

	private fun otherBuilds(
		pluginId: String,
		fileName: String,
	): List<File> =
		pluginsDir
			.listFiles { file ->
				file.isFile &&
					file.name != fileName &&
					file.name.endsWith(".$PLUGIN_ARCHIVE_EXTENSION", ignoreCase = true) &&
					runCatching { readPluginId(file) }.getOrNull() == pluginId
			}.orEmpty()
			.toList()

	internal companion object {
		private const val MAX_FILE_NAME_BYTES = 255

		const val SIGNED_BY_ANOTHER_KEY = "a plugin with its ID is already installed, signed with a different key"

		const val ONE_UNSIGNED = "a plugin with its ID is already installed, and one of the two has no signature, so they can't be matched"

		// The platform parser also refuses a plugin whose minSdkVersion is above the device's
		// (AI-Core, 33, on Android 9), and the two cases look alike from here.
		const val UNREADABLE_MANIFEST = "its manifest can't be read: it may need a newer version of Android, or be damaged"
	}

	private class RejectedAddonException(
		message: String,
	) : IOException(message)

	private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

	private fun InputStream.copyToFile(dest: File) {
		dest.outputStream().use { out -> copyTo(out) }
	}
}
