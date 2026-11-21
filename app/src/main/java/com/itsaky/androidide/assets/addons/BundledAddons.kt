package com.itsaky.androidide.assets.addons

import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
import java.io.InputStream
import java.security.MessageDigest

/** The add-on kinds a build can bundle, each in a folder of its own in the APK assets or the debug zip. */
enum class BundledAddonKind(
	val dir: String,
	val extension: String,
) {
	PLUGIN("plugins", PLUGIN_ARCHIVE_EXTENSION),
	TEMPLATE("templates", TEMPLATE_ARCHIVE_EXTENSION),
}

/** One bundled add-on file: [fileName] inside the [kind] folder, [size] bytes. */
data class BundledEntry(
	val kind: BundledAddonKind,
	val fileName: String,
	val size: Long,
) {
	/** The name the installers and the progress list know it by: `plugins/<fileName>`. */
	val entryName: String get() = "${kind.dir}/$fileName"
}

/** Where the bundle is read from: APK assets in a release build, the split assets zip in a debug one. */
interface BundleSource {
	/** File names directly inside [dir], or none when the folder is absent. */
	fun names(dir: String): List<String>

	fun size(
		dir: String,
		name: String,
	): Long

	fun open(
		dir: String,
		name: String,
	): InputStream
}

object BundledAddons {
	/** True for an entry name that [BundledEntry.entryName] could have produced. */
	fun isAddonEntry(entryName: String): Boolean = parse(entryName) != null

	/** The kind and file name of [entryName], or null when it names no add-on file. */
	fun parse(entryName: String): Pair<BundledAddonKind, String>? {
		val kind = BundledAddonKind.values().firstOrNull { entryName.startsWith("${it.dir}/") } ?: return null
		val fileName = entryName.removePrefix("${kind.dir}/")
		return if (isAddonFile(kind, fileName)) kind to fileName else null
	}

	/**
	 * Every add-on file [source] holds, plugins first, each kind in name order. A thin build has
	 * none. Other files in the folders, and any path below them, are not add-ons.
	 */
	fun list(source: BundleSource): List<BundledEntry> =
		BundledAddonKind.values().flatMap { kind ->
			source
				.names(kind.dir)
				.filter { isAddonFile(kind, it) }
				.sorted()
				.map { BundledEntry(kind, it, source.size(kind.dir, it)) }
		}

	/**
	 * Identifies a bundle by its entry names and sizes, in any order: sha-256, hex. Cheap enough to
	 * recompute at every start. A rebuilt add-on that keeps its name and size is not noticed. The
	 * empty bundle has a fingerprint too, so a thin build can be told from a missing record.
	 */
	fun fingerprint(entries: List<BundledEntry>): String {
		val digest = MessageDigest.getInstance("SHA-256")
		entries
			.map { "${it.entryName}\t${it.size}\n" }
			.sorted()
			.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	private fun isAddonFile(
		kind: BundledAddonKind,
		fileName: String,
	): Boolean =
		'/' !in fileName &&
			!fileName.startsWith(".") &&
			fileName.endsWith(".${kind.extension}", ignoreCase = true) &&
			// core.cgt is installed by its own entry into the same folder.
			!(kind == BundledAddonKind.TEMPLATE && fileName.equals(TEMPLATE_CORE_ARCHIVE, ignoreCase = true))
}
