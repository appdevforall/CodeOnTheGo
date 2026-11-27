package com.itsaky.androidide.assets.addons

import java.io.File

/**
 * Applies the build's bundle to an installation that already has one (ADFA-7239): after an update
 * to an APK with another bundle, or after a first installation that stopped before its record was
 * written.
 *
 * The new bundle replaces the recorded one. Every bundled add-on is installed, overwriting a user's
 * plugin with the same ID if it is signed with the same key (one signed with another key stays, and
 * the bundled plugin is reported instead) and a user's template with the same name, and bringing back
 * one the user removed. A recorded add-on the new bundle lacks is removed, so a thin APK removes them all. Other
 * user add-ons are left alone.
 *
 * A bundled plugin whose build is already the only one installed for its ID is copied over in
 * place and keeps its state (enabled or not, crash count); only a real change of build goes
 * through [uninstallPlugin], which clears that state, as an install from the Add-ons Manager does.
 *
 * The record is written last, so a sync cut short runs again at the next start.
 *
 * @param readPluginId the plugin ID in a plugin file's manifest, or null.
 * @param signatureOf the signing certificate hash of a plugin file, or null if it can't be read.
 * @param uninstallPlugin removes every installed file of a plugin ID, with its state.
 */
class BundleSync(
	private val record: BundledAddonsRecord,
	private val pluginsDir: File,
	private val templatesDir: File,
	private val readPluginId: (File) -> String?,
	private val signatureOf: (File) -> ByteArray?,
	private val uninstallPlugin: (pluginId: String) -> Unit,
) {
	// The installer removes another build of a bundled ID only once its signature matches; uninstalling
	// clears that plugin's state too. An unchanged build lands in place and is not uninstalled.
	private val installer =
		BundledAddonInstaller(pluginsDir, templatesDir, readPluginId, signatureOf) { pluginId, _ -> uninstallPlugin(pluginId) }

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

		// A rejected copy is not known by ID, so it may be a recorded add-on the new bundle still has:
		// removing by the old record would then delete a working build. Keep the old ones instead.
		if (installed.none { it.failure != null }) {
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
		}

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
}
