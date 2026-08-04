package org.appdevforall.cotg.quickbuild.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages changed asset files into the deploy payload zip.
 *
 * Entry names are asset-relative paths with forward slashes (`data/levels.json`) - the same
 * strings the deploy metadata's `changedAssets` array carries, so the runtime overlays them
 * 1:1.
 */
class AssetPackager {
	/**
	 * Maps [file] to its path relative to whichever of [assetRoots] contains it, or null if
	 * none does. Used both to pick asset files out of a changed set and to name their zip
	 * entries.
	 *
	 * @param file candidate path; need not exist, since containment is decided on the path
	 *   text alone.
	 * @param assetRoots asset roots to test, in order; the first one containing [file] wins.
	 * @return the '/'-separated path relative to the matching root, or null when [file] lies
	 *   under none of them (a root itself never matches - only strict descendants do).
	 */
	fun relativeAssetPath(
		file: File,
		assetRoots: List<File>,
	): String? {
		val abs = file.absoluteFile
		for (root in assetRoots) {
			val rootAbs = root.absoluteFile
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
	 * @return the written zip and the relative entry paths, or null when the changed
	 *   set contains no asset files - callers then omit the assets payload entirely. A deleted
	 *   asset still counts toward non-null but writes no entry (absence is the v1 signal).
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
	 * @property relativePaths sorted, '/'-separated asset-relative entry names - the exact
	 *   strings the deploy metadata's `changedAssets` array carries. Includes deleted assets,
	 *   which have no entry in [zip].
	 */
	data class PackagedAssets(
		val zip: File,
		val relativePaths: List<String>,
	)
}
