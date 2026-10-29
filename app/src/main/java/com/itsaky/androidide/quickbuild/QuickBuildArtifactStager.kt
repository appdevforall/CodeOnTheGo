package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.itsaky.androidide.utils.Environment
import org.adfa.constants.GRADLE_DISTRIBUTION_NAME
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Stages the quick-build runtime AAR and daemon under `<ANDROIDIDE_HOME>/quickbuild/`, linking
 * the jars [LINKED_JARS_LIST] names in from the on-device Gradle distribution.
 *
 * The daemon is re-staged only when the APK was reinstalled or a linked jar stopped resolving,
 * which skips a 1.9 MB extraction per provision and keeps the wipe away from a live daemon.
 */
object QuickBuildArtifactStager {
	private val log = LoggerFactory.getLogger("QB-ArtifactStager")

	private const val ASSET_RUNTIME_AAR = "data/common/quickbuild-runtime.aar"
	private const val ASSET_DAEMON_ZIP = "data/common/quickbuild-daemon.zip"

	/** Written last, after a complete extraction, so a crash mid-extract leaves no stamp. */
	internal const val DAEMON_STAMP_FILE = ".staged-for-install"

	/** Written into the daemon zip by :app's `quickBuildDistLinkedJarList`. */
	internal const val LINKED_JARS_LIST = "dist-linked-jars.txt"

	/** @throws IOException when an asset is missing or extraction fails. */
	@Throws(IOException::class)
	fun stage(
		context: Context,
		paths: EnvironmentQuickBuildPaths,
	) {
		stageRuntimeAar(context, paths.runtimeAar)
		stageDaemonIfNeeded(installStamp(context), paths.daemonDir, paths.daemonJar) {
			context.assets.open(ASSET_DAEMON_ZIP).buffered()
		}
	}

	/**
	 * Identity of the installed APK. lastUpdateTime, unlike a version constant, moves on every reinstall.
	 */
	private fun installStamp(context: Context): String {
		val info = context.packageManager.getPackageInfo(context.packageName, 0)
		return "${PackageInfoCompat.getLongVersionCode(info)}:${info.lastUpdateTime}"
	}

	private fun stageRuntimeAar(
		context: Context,
		target: File,
	) {
		target.parentFile?.let(Environment::mkdirIfNotExists)
		context.assets.open(ASSET_RUNTIME_AAR).use { input ->
			target.outputStream().use { input.copyTo(it) }
		}
		log.info("Staged quick-build runtime AAR at {}", target)
	}

	/**
	 * Wipes and re-extracts [daemonDir] unless it already holds a complete extraction for
	 * [installStamp]: the stamp matches, [daemonJar] is present and every linked jar resolves.
	 * Internal so the JVM test can watch the skip, and the wipe, without an Android [Context].
	 *
	 * @param gradleDists exists so tests can point at a fake distribution.
	 * @return whether an extraction ran.
	 */
	@Throws(IOException::class)
	internal fun stageDaemonIfNeeded(
		installStamp: String,
		daemonDir: File,
		daemonJar: File,
		gradleDists: File = Environment.GRADLE_DISTS,
		openZip: () -> InputStream,
	): Boolean {
		val stamp = File(daemonDir, DAEMON_STAMP_FILE)
		if (daemonJar.isFile && stamp.isFile && stamp.readText() == installStamp && linkedJarsResolve(daemonDir)) {
			log.info("Daemon already staged for this install at {}", daemonDir)
			return false
		}
		if (daemonDir.exists()) {
			daemonDir.deleteRecursively()
		}
		Environment.mkdirIfNotExists(daemonDir)

		val count = extractDaemonZip(openZip(), daemonDir)
		// Must run before the stamp is written, or a failed link would be stamped as done and
		// never retried.
		linkDistJars(daemonDir, gradleDists)
		stamp.writeText(installStamp)
		log.info("Staged {} daemon files into {}", count, daemonDir)
		return true
	}

	/**
	 * Unpacks the daemon zip from [input] into [daemonDir]. Internal so the JVM test can watch
	 * the zip-slip guard go red without an Android [Context].
	 *
	 * @return the number of files extracted.
	 * @throws IOException on a zip entry escaping [daemonDir].
	 * @throws FileNotFoundException when the zip contains no files.
	 */
	@Throws(IOException::class)
	internal fun extractDaemonZip(
		input: InputStream,
		daemonDir: File,
	): Int {
		val canonicalRoot = daemonDir.canonicalFile
		ZipInputStream(input).use { zip ->
			var entry = zip.nextEntry
			var count = 0
			while (entry != null) {
				val out = File(daemonDir, entry.name)
				// zip-slip guard: never write outside the daemon dir
				if (!out.canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
					throw IOException("Refusing zip entry escaping daemon dir: ${entry.name}")
				}
				if (entry.isDirectory) {
					Environment.mkdirIfNotExists(out)
				} else {
					out.parentFile?.let(Environment::mkdirIfNotExists)
					out.outputStream().use { zip.copyTo(it) }
					count++
				}
				zip.closeEntry()
				entry = zip.nextEntry
			}
			if (count == 0) {
				throw FileNotFoundException("Daemon zip contained no files")
			}
			return count
		}
	}

	/**
	 * Links the jars the APK leaves out into the daemon dir, where its manifest Class-Path
	 * expects them.
	 *
	 * @param gradleDists exists so tests can point at a fake distribution.
	 * @param createLink exists so tests can force the copy fallback.
	 * @throws FileNotFoundException if a jar or the list is missing, which fails loudly here
	 *   rather than as a NoClassDefFoundError during the user's first compile.
	 */
	@Throws(IOException::class)
	internal fun linkDistJars(
		daemonDir: File,
		gradleDists: File = Environment.GRADLE_DISTS,
		createLink: (Path, Path) -> Unit = { link, existing -> Files.createSymbolicLink(link, existing) },
	) {
		val distLib = File(File(gradleDists, GRADLE_DISTRIBUTION_NAME), "lib")
		for (jarName in readLinkedJarNames(daemonDir)) {
			val source = File(distLib, jarName)
			if (!source.isFile) {
				throw FileNotFoundException(
					"$jarName missing from the on-device Gradle distribution: $source. " +
						"Quick Build loads it from there rather than from the APK.",
				)
			}

			val target = File(daemonDir, jarName)
			// Clear the path first: a link an earlier staging left would make the copy fallback
			// follow it and truncate the distribution's own jar.
			Files.deleteIfExists(target.toPath())
			try {
				createLink(target.toPath(), source.toPath())
			} catch (e: IOException) {
				copyInstead(source, target, e)
			} catch (e: UnsupportedOperationException) {
				copyInstead(source, target, e)
			}
		}
	}

	/** Copies [source] to [target] when the filesystem refused a link, so the bytes still arrive. */
	private fun copyInstead(
		source: File,
		target: File,
		cause: Exception,
	) {
		log.warn("Symlink {} -> {} failed ({}), copying", target, source, cause.toString())
		source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
	}

	/**
	 * Whether every linked jar in [daemonDir] still reaches a file. The stamp alone cannot say,
	 * since the assets installers delete and re-extract the distribution the links point into.
	 */
	private fun linkedJarsResolve(daemonDir: File): Boolean {
		// An unreadable list re-stages too, and the re-stage's own read reports why.
		val names =
			try {
				readLinkedJarNames(daemonDir)
			} catch (e: IOException) {
				return false
			}
		return names.all { File(daemonDir, it).isFile }
	}

	/** The jar names the build left out of the zip for the distribution to supply. */
	private fun readLinkedJarNames(daemonDir: File): List<String> {
		val list = File(daemonDir, LINKED_JARS_LIST)
		if (!list.isFile) {
			throw FileNotFoundException(
				"$LINKED_JARS_LIST is missing from $daemonDir, so the build never wrote it. It " +
					"lists the jars Quick Build links from the Gradle distribution.",
			)
		}
		val names = list.readLines().map(String::trim).filter(String::isNotEmpty)
		if (names.isEmpty()) {
			throw FileNotFoundException(
				"$LINKED_JARS_LIST at $list names no jars. It lists the jars Quick Build links " +
					"from the Gradle distribution rather than shipping in the APK.",
			)
		}
		// Each name is used as a path on both sides of the link, so the same boundary
		// extractDaemonZip guards applies here: anything but a bare file name escapes.
		names.firstOrNull { File(it).name != it }?.let { name ->
			throw IOException("Refusing linked jar name that is not a bare file name: $name")
		}
		return names
	}
}
