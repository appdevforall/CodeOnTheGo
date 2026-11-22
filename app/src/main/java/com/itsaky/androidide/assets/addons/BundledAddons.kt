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

/**
 * One bundled add-on file: [fileName] inside the [kind] folder, [size] bytes. [sha256] (hex) comes
 * from the bundle index; it is null for a bundle without one, listed by name and size.
 */
data class BundledEntry(
	val kind: BundledAddonKind,
	val fileName: String,
	val size: Long,
	val sha256: String? = null,
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

	/** The text of the bundle index ([BundledAddons.INDEX_FILE]), or null when the bundle has none. */
	fun index(): String? = null
}

object BundledAddons {
	/**
	 * The bundle index, written by the build beside `plugins/` and `templates/`: one line per add-on,
	 * `<entryName>\t<size>\t<sha256 hex>`. A thin build writes it empty. With it, listing the bundle
	 * opens no add-on, and the fingerprint notices a rebuilt add-on of the same size.
	 */
	const val INDEX_FILE = "bundled-addons.tsv"

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
	 * none. The bundle index is used when there is a valid one. Otherwise, as for a debug zip made
	 * before the index, the folders are listed and each file's size read: other files in the folders,
	 * and any path below them, are not add-ons.
	 */
	fun list(source: BundleSource): List<BundledEntry> = source.index()?.let(::parseIndex) ?: listFolders(source)

	/**
	 * The entries of an index, in [list]'s order, or null when any line is malformed, names no add-on
	 * file, or repeats an entry: such an index was not written by the build, so the folders decide.
	 */
	fun parseIndex(text: String): List<BundledEntry>? {
		val entries =
			text.lines().filter { it.isNotBlank() }.map { line ->
				val fields = line.split('\t')
				if (fields.size != 3) return null
				val (kind, fileName) = parse(fields[0]) ?: return null
				val size = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
				val sha256 = fields[2].takeIf { SHA256_HEX.matches(it) } ?: return null
				BundledEntry(kind, fileName, size, sha256)
			}
		if (entries.distinctBy { it.entryName }.size != entries.size) return null
		return entries.sortedWith(compareBy({ it.kind.ordinal }, { it.fileName }))
	}

	private val SHA256_HEX = Regex("[0-9a-f]{64}")

	private fun listFolders(source: BundleSource): List<BundledEntry> =
		BundledAddonKind.values().flatMap { kind ->
			source
				.names(kind.dir)
				.filter { isAddonFile(kind, it) }
				.sorted()
				.map { BundledEntry(kind, it, source.size(kind.dir, it)) }
		}

	/**
	 * Identifies a bundle by its entries, in any order: sha-256, hex. It reads no add-on, so it is
	 * cheap at every start. Entries from an index carry their content hash, so a rebuilt add-on of the
	 * same name and size is noticed; a bundle without an index is identified by names and sizes only.
	 * The empty bundle has a fingerprint too, so a thin build can be told from a missing record.
	 */
	fun fingerprint(entries: List<BundledEntry>): String {
		val digest = MessageDigest.getInstance("SHA-256")
		entries
			.map { entry -> listOfNotNull(entry.entryName, entry.size.toString(), entry.sha256).joinToString("\t", postfix = "\n") }
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
