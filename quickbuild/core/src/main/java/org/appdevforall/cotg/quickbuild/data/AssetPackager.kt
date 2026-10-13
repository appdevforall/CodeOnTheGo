package org.appdevforall.cotg.quickbuild.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages changed asset files into the deploy payload zip.
 *
 * Entry names are asset-relative paths with forward slashes (`data/levels.json`), which is how
 * the runtime's asset overlay keys them, so an entry lands 1:1 over the asset it replaces.
 */
class AssetPackager {
	/**
	 * Maps [file] to its path relative to whichever of [assetRoots] contains it, or null if none
	 * does.
	 *
	 * Both sides are normalized first: without that, `<root>/sub/../../evil` passes the raw-text
	 * containment test and names a zip entry that escapes the asset directory on unpack.
	 *
	 * @param file candidate path; need not exist, since containment is decided on the path text
	 *   alone.
	 * @param assetRoots asset roots to test, in order; the first one containing [file] wins.
	 * @return the '/'-separated path relative to the matching root, or null when [file] lies under
	 *   none of them (a root itself never matches), never containing a `..` segment.
	 */
	fun relativeAssetPath(
		file: File,
		assetRoots: List<File>,
	): String? {
		val abs = file.absoluteFile.normalize()
		for (root in assetRoots) {
			val rootAbs = root.absoluteFile.normalize()
			val rootPath = rootAbs.path + File.separator
			if (abs.path.startsWith(rootPath)) {
				return abs.path.removePrefix(rootPath).replace(File.separatorChar, '/')
			}
		}
		return null
	}

	/**
	 * Zips [changedFiles] (only those under an asset root) into [outFile].
	 *
	 * @param changedFiles this build's changed set, assets and non-assets mixed; entries
	 *   outside every asset root are ignored.
	 * @param assetRoots the module's asset roots, which name the zip entries.
	 * @param outFile zip to write; overwritten, and its parent directory is created.
	 * @return the written zip and the relative entry paths, or null when the changed set
	 *   contains no asset files, in which case callers omit the assets payload entirely.
	 */
	fun packageAssets(
		changedFiles: Collection<File>,
		assetRoots: List<File>,
		outFile: File,
	): PackagedAssets? {
		val entries =
			changedFiles.mapNotNull { file ->
				relativeAssetPath(file, assetRoots)?.let { rel -> rel to file }
			}
		if (entries.isEmpty()) return null

		outFile.parentFile?.mkdirs()
		ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
			for ((rel, file) in entries.sortedBy { it.first }) {
				if (!file.isFile) continue // deleted asset: absence is the signal for v1
				zip.putNextEntry(ZipEntry(rel))
				file.inputStream().use { it.copyTo(zip) }
				zip.closeEntry()
			}
		}
		return PackagedAssets(outFile, entries.map { it.first }.sorted())
	}

	/**
	 * A written assets zip and the entry paths inside it.
	 *
	 * @property zip the file just written; always exists, even when every changed asset was a
	 *   deletion and the archive is therefore empty.
	 * @property relativePaths sorted, '/'-separated asset-relative entry names, including deleted
	 *   assets that have no entry in [zip], so this is a superset of the archive's contents.
	 */
	data class PackagedAssets(
		val zip: File,
		val relativePaths: List<String>,
	)
}
