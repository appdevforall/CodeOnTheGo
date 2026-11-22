package com.itsaky.androidide.assets.addons

import java.io.File

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
 * The record is written last, so a sync cut short runs again at the next start; installing a
 * bundle twice gives the same files.
 *
 * @param installer copies one bundled file; built with [uninstallPlugin] as its
 *   `beforePluginMove`, so an older build of the same ID is gone before the new one lands.
 * @param uninstallPlugin removes every installed file of a plugin ID, with its state.
 */
class BundleSync(
	private val record: BundledAddonsRecord,
	private val installer: BundledAddonInstaller,
	private val templatesDir: File,
	private val uninstallPlugin: (pluginId: String) -> Unit,
) {
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
				source.open(entry.kind.dir, entry.fileName).use { installer.install(entry.kind, entry.fileName, it) }
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
}
