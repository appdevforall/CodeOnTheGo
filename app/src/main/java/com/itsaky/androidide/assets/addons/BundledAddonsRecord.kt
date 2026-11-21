package com.itsaky.androidide.assets.addons

import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

/** A bundled plugin that did not load, and whether the user has been told. */
data class BundledPluginFailure(
	val pluginId: String,
	val message: String,
	val reported: Boolean = false,
)

/** What the first installation took from the build's bundle. */
data class BundledAddonsState(
	/** [BundledAddons.fingerprint] of the bundle that was installed. */
	val fingerprint: String,
	val pluginIds: List<String> = emptyList(),
	val templateFiles: List<String> = emptyList(),
	val failures: List<BundledPluginFailure> = emptyList(),
)

/**
 * The record of an installed bundle: which plugins and templates came from the build, and which
 * bundled plugins failed to load. Kept in a properties file, as `plugin_crash_counts.properties` is.
 *
 * [write] runs only after every bundle file is in place, so a record exists only for a complete
 * install, and replaces the file in one step. A missing or unreadable file reads as no record.
 * The installation writes it for a thin build too, with an empty bundle.
 */
class BundledAddonsRecord(
	private val file: File,
) {
	@Synchronized
	fun read(): BundledAddonsState? {
		if (!file.isFile) return null
		return try {
			val props = Properties()
			file.inputStream().use { props.load(it) }
			val fingerprint = props.getProperty(FINGERPRINT) ?: return null
			BundledAddonsState(
				fingerprint = fingerprint,
				pluginIds = props.indexed(PLUGIN),
				templateFiles = props.indexed(TEMPLATE),
				failures = props.failures(),
			)
		} catch (e: IOException) {
			null
		} catch (e: IllegalArgumentException) {
			// Properties.load on a malformed escape.
			null
		}
	}

	/** Replaces the record with [state]; failures it carries are kept as given. */
	@Synchronized
	fun write(state: BundledAddonsState) {
		val props = Properties()
		props.setProperty(FINGERPRINT, state.fingerprint)
		state.pluginIds.forEachIndexed { n, id -> props.setProperty("$PLUGIN.$n", id) }
		state.templateFiles.forEachIndexed { n, name -> props.setProperty("$TEMPLATE.$n", name) }
		state.failures.forEachIndexed { n, failure ->
			props.setProperty("$FAILURE.$n.id", failure.pluginId)
			props.setProperty("$FAILURE.$n.message", failure.message)
			props.setProperty("$FAILURE.$n.reported", failure.reported.toString())
		}

		file.parentFile?.mkdirs()
		val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
		try {
			temp.outputStream().use { props.store(it, null) }
			AtomicFiles.moveInto(temp, file)
		} finally {
			temp.delete()
		}
	}

	/**
	 * Stores [failures] (plugin ID to message) as unreported. A failure already recorded under
	 * another key (a plugin rejected while copying) is kept; one under the same key is replaced.
	 * Does nothing when there is no record: failures belong to an installed bundle.
	 */
	@Synchronized
	fun recordFailures(failures: Map<String, String>) {
		val state = read() ?: return
		val kept = state.failures.filterNot { it.pluginId in failures }
		write(state.copy(failures = kept + failures.map { (id, message) -> BundledPluginFailure(id, message) }))
	}

	/** The failures the user has not been told about, in the order recorded. */
	@Synchronized
	fun unreportedFailures(): List<BundledPluginFailure> = read()?.failures.orEmpty().filterNot { it.reported }

	/** Marks every recorded failure as told, and returns those that were not before. */
	@Synchronized
	fun markReported(): List<BundledPluginFailure> {
		val state = read() ?: return emptyList()
		val pending = state.failures.filterNot { it.reported }
		if (pending.isNotEmpty()) {
			write(state.copy(failures = state.failures.map { it.copy(reported = true) }))
		}
		return pending
	}

	private fun Properties.indexed(prefix: String): List<String> {
		val values = ArrayList<String>()
		while (true) {
			values += getProperty("$prefix.${values.size}") ?: return values
		}
	}

	private fun Properties.failures(): List<BundledPluginFailure> {
		val failures = ArrayList<BundledPluginFailure>()
		while (true) {
			val n = failures.size
			val id = getProperty("$FAILURE.$n.id") ?: return failures
			failures +=
				BundledPluginFailure(
					pluginId = id,
					message = getProperty("$FAILURE.$n.message").orEmpty(),
					reported = getProperty("$FAILURE.$n.reported") == "true",
				)
		}
	}

	companion object {
		const val FILE_NAME = "bundled_addons.properties"

		private const val FINGERPRINT = "fingerprint"
		private const val PLUGIN = "plugin"
		private const val TEMPLATE = "template"
		private const val FAILURE = "failure"

		fun inFilesDir(filesDir: File) = BundledAddonsRecord(File(filesDir, FILE_NAME))
	}
}
