package com.itsaky.androidide.utils

import com.itsaky.androidide.models.ApkMetadata
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/** Finds a built APK and copies it out of app storage under a readable name (ADFA-4930). */
internal object BuildExport {
	const val MIME_TYPE = "application/vnd.android.package-archive"

	private val log = LoggerFactory.getLogger(BuildExport::class.java)

	/** What [resolve] found to export. */
	sealed interface Exportable {
		data class Apk(
			val file: File,
		) : Exportable

		/** No APK yet, or its listing cannot be read. */
		data object NotBuilt : Exportable

		/**
		 * The APK at the variant's output path is Quick Build's live-reload proxy app, which its
		 * build writes there; exporting it would hand over the wrong app.
		 */
		data object QuickBuildProxy : Exportable
	}

	/**
	 * @param listingFile the variant's APK listing file, from the synced model.
	 * @param quickBuildReport the variant's Quick Build `setup.json`, which names the proxy APK.
	 * @param projectDir the project's root, which the report's relative paths resolve against.
	 */
	fun resolve(
		listingFile: File,
		quickBuildReport: File,
		projectDir: File,
	): Exportable {
		val apk =
			try {
				ApkMetadata.findApkFile(listingFile)?.takeIf { it.isFile }
			} catch (e: Exception) {
				// A truncated or malformed listing (JSON or redirect) means there is no APK to trust.
				log.warn("Cannot read the APK listing {}", listingFile, e)
				null
			} ?: return Exportable.NotBuilt
		return if (isQuickBuildProxy(apk, quickBuildReport, projectDir)) Exportable.QuickBuildProxy else Exportable.Apk(apk)
	}

	/**
	 * Quick Build's report is written after the proxy APK it names, so the APK is the proxy when
	 * the report names it and is not older than it. A standard build rewrites the APK and leaves
	 * the report alone, so after one the APK is newer.
	 */
	private fun isQuickBuildProxy(
		apk: File,
		report: File,
		projectDir: File,
	): Boolean {
		if (!report.isFile) return false
		val reported =
			try {
				ProxyAppInfo.parse(report.readText(), projectDir)?.apk
			} catch (e: IOException) {
				null
			} ?: return false
		return reported.absoluteFile.normalize() == apk.absoluteFile.normalize() && report.lastModified() >= apk.lastModified()
	}

	/**
	 * Copies [apk] into [dir] as `<name>.apk`, or `<name> (2).apk` and so on when that is taken, so
	 * an earlier export is never overwritten. The copy goes to a temporary name first, so one that
	 * fails part-way never leaves a broken APK under the export's name.
	 *
	 * @param name the file name without `.apk`; characters a file name cannot hold are replaced.
	 * @return the copy.
	 * @throws IOException when [dir] cannot be created or the copy fails.
	 */
	fun copyTo(
		apk: File,
		dir: File,
		name: String,
		copy: (File, File) -> Unit = { from, to -> from.copyTo(to) },
	): File {
		if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
		val target = freeTarget(dir, safeFileName(name, "app"))
		val partial = File(dir, ".${target.name}.partial")
		try {
			partial.delete()
			copy(apk, partial)
			if (!partial.renameTo(target)) throw IOException("Cannot rename $partial to $target")
		} finally {
			partial.delete()
		}
		return target
	}

	private fun freeTarget(
		dir: File,
		name: String,
	): File =
		generateSequence(1) { it + 1 }
			.map { n -> File(dir, if (n == 1) "$name.apk" else "$name ($n).apk") }
			.first { !it.exists() }
}
