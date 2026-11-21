package com.itsaky.androidide.assets.addons

import kotlin.coroutines.cancellation.CancellationException

/**
 * Starts the plugins a first installation copied from the bundle, and records those that did not
 * load, for the main screen to report once ([BundledAddonsRecord.markReported]).
 *
 * A failure here never fails the setup: [start] returns normally whatever the plugins do.
 */
class BundledPluginStartup(
	private val record: BundledAddonsRecord,
	private val reloadPlugins: suspend () -> Unit,
	private val loadError: (pluginId: String) -> String?,
) {
	suspend fun start() {
		val pluginIds = record.read()?.pluginIds.orEmpty()
		// A thin build copied no plugin, so there is nothing to start.
		if (pluginIds.isEmpty()) return

		val reloadFailure =
			try {
				reloadPlugins()
				null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				e.message ?: e.toString()
			}

		val failures =
			pluginIds
				.mapNotNull { id -> (reloadFailure ?: loadError(id))?.let { id to it } }
				.toMap()
		if (failures.isNotEmpty()) {
			record.recordFailures(failures)
		}
	}
}
