package org.appdevforall.cotg.quickbuild.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages changed asset files into the deploy payload zip. Entry names are the
 * asset-relative paths (`data/levels.json`, forward slashes) - exactly the strings the
 * deploy metadata's `changedAssets` array carries, so the runtime can overlay them 1:1.
 */
class AssetPackager {
	/**
	 * The path of [file] relative to the asset root that contains it, or null when the
	 * file is not under any of [assetRoots]. Used both to select asset files out of a
	 * changed-set and to name their zip entries.
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
	 * @return the written zip and the relative entry paths, or null when the changed
	 *   set contains no asset files - callers then omit the assets payload entirely.
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

	data class PackagedAssets(
		val zip: File,
		val relativePaths: List<String>,
	)
}
