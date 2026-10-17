package com.itsaky.androidide.utils

import com.itsaky.androidide.models.ApkMetadata
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Finds what a build produced - an APK, an App Bundle or a plugin - and copies it out of app storage
 * under a readable name (ADFA-4930).
 */
internal object BuildExport {
	private val log = LoggerFactory.getLogger(BuildExport::class.java)

	/** A kind of build output, with what a share or a copy of it needs. */
	enum class Kind(
		val extension: String,
		val mimeType: String,
	) {
		APK("apk", "application/vnd.android.package-archive"),

		// Neither has a registered MIME type.
		BUNDLE("aab", "application/octet-stream"),
		PLUGIN(PLUGIN_ARCHIVE_EXTENSION, "application/octet-stream"),
	}

	/** A built file to export. */
	data class Output(
		val kind: Kind,
		val file: File,
	)

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
	 * What a variant built that can be exported, in the order offered: APK, App Bundle, plugin.
	 *
	 * @param apk what [resolve] found for the variant.
	 * @param bundle from [findBundle].
	 * @param plugin a plugin project's `.cgp` for the variant.
	 */
	fun outputs(
		apk: Exportable,
		bundle: File?,
		plugin: File?,
	): List<Output> =
		listOfNotNull(
			(apk as? Exportable.Apk)?.let { Output(Kind.APK, it.file) },
			bundle?.let { Output(Kind.BUNDLE, it) },
			plugin?.let { Output(Kind.PLUGIN, it) },
		)

	/**
	 * The newest App Bundle `bundle<Variant>` wrote for [variantName]. The synced model has no bundle
	 * listing, so this is AGP's fixed location under the module's build directory.
	 *
	 * @param buildDir the module's build directory, from the synced model.
	 */
	fun findBundle(
		buildDir: File,
		variantName: String,
	): File? =
		File(buildDir, "outputs/bundle/$variantName")
			.listFiles { file -> file.isFile && file.extension == Kind.BUNDLE.extension }
			?.maxByOrNull { it.lastModified() }

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
	 * Copies [file] into [dir] as `<name>.<extension>`, or `<name> (2).<extension>` and so on when
	 * that is taken, so an earlier export is never overwritten. The copy goes to a temporary name
	 * first, so one that fails part-way never leaves a broken file under the export's name.
	 *
	 * @param name the file name without its extension; characters a file name cannot hold are
	 *   replaced.
	 * @return the copy.
	 * @throws IOException when [dir] cannot be created or the copy fails.
	 */
	fun copyTo(
		file: File,
		dir: File,
		name: String,
		extension: String,
		copy: (File, File) -> Unit = { from, to -> from.copyTo(to) },
	): File {
		if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
		val target = freeTarget(dir, safeFileName(name, "app"), extension)
		val partial = File(dir, ".${target.name}.partial")
		try {
			partial.delete()
			copy(file, partial)
			if (!partial.renameTo(target)) throw IOException("Cannot rename $partial to $target")
		} finally {
			partial.delete()
		}
		return target
	}

	private fun freeTarget(
		dir: File,
		name: String,
		extension: String,
	): File =
		generateSequence(1) { it + 1 }
			.map { n -> File(dir, if (n == 1) "$name.$extension" else "$name ($n).$extension") }
			.first { !it.exists() }
}
