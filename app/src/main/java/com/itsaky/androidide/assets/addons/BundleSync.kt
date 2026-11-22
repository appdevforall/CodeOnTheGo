package com.itsaky.androidide.assets.addons

import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import java.io.File
import java.io.IOException

/**
 * Applies the build's bundle to an installation that already has one (ADFA-7239): after an update
 * to an APK with another bundle, or after a first installation that stopped before its record was
 * written.
 *
 * The new bundle replaces the recorded one. Every bundled add-on is installed, overwriting a user's
 * plugin with the same ID and a user's template with the same name, and bringing back one the user
 * removed. A recorded add-on the new bundle lacks is removed, so a thin APK removes them all. Other
 * user add-ons are left alone.
 *
 * A bundled plugin whose build is already the only one installed for its ID is copied over in
 * place and keeps its state (enabled or not, crash count); only a real change of build goes
 * through [uninstallPlugin], which clears that state, as an install from the Add-ons Manager does.
 *
 * The record is written last, so a sync cut short runs again at the next start.
 *
 * @param readPluginId the plugin ID in a plugin file's manifest, or null.
 * @param uninstallPlugin removes every installed file of a plugin ID, with its state.
 */
class BundleSync(
	private val record: BundledAddonsRecord,
	private val pluginsDir: File,
	private val templatesDir: File,
	private val readPluginId: (File) -> String?,
	private val uninstallPlugin: (pluginId: String) -> Unit,
) {
	private val installer = BundledAddonInstaller(pluginsDir, templatesDir, readPluginId, ::replaceOtherBuilds)

	/** True when [entries] are not the recorded bundle, or no bundle is recorded. */
	fun isNeeded(entries: List<BundledEntry>): Boolean = record.read()?.fingerprint != BundledAddons.fingerprint(entries)

	/**
	 * Installs [entries] from [source] in place of the recorded bundle, and records them. An I/O
	 * error propagates and leaves the old record, so the sync runs again.
	 */
	fun sync(
		source: BundleSource,
		entries: List<BundledEntry>,
	): BundledAddonsState {
		val old = record.read()

		val installed =
			entries.map { entry ->
				source.open(entry.kind.dir, entry.fileName).use { installer.install(entry.kind, entry.fileName, it, entry.sha256) }
			}

		val pluginIds = installed.mapNotNull { it.pluginId }.toSet()
		val templateFiles = installed.filter { it.kind == BundledAddonKind.TEMPLATE }.mapNotNull { it.file?.name }.toSet()

		old
			?.pluginIds
			.orEmpty()
			.filterNot { it in pluginIds }
			.forEach(uninstallPlugin)
		old
			?.templateFiles
			.orEmpty()
			.filterNot { it in templateFiles }
			.forEach { name -> File(templatesDir, name).takeIf { it.parentFile == templatesDir }?.delete() }

		val state =
			BundledAddonsState(
				fingerprint = BundledAddons.fingerprint(entries),
				pluginIds = pluginIds.sorted(),
				templateFiles = templateFiles.sorted(),
				// The old bundle's failures went with it; these are the new one's rejected copies.
				failures =
					installed
						.mapNotNull { addon -> addon.failure?.let { BundledPluginFailure(addon.sourceName ?: "?", it) } }
						.sortedBy { it.pluginId },
			)
		record.write(state)
		return state
	}

	/**
	 * Before the bundled build [fileName] of [pluginId] lands: removes the other builds of that ID,
	 * a user's or an older bundled one. When none is left, the move overwrites the same build in
	 * place and nothing is uninstalled. Throws when a build stays, so the two never load together.
	 */
	private fun replaceOtherBuilds(
		pluginId: String,
		fileName: String,
	) {
		if (otherBuilds(pluginId, fileName).isEmpty()) return
		uninstallPlugin(pluginId)
		val left = otherBuilds(pluginId, fileName)
		if (left.isNotEmpty()) {
			throw IOException("Could not remove the installed build of $pluginId: ${left.joinToString { it.name }}")
		}
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
}
