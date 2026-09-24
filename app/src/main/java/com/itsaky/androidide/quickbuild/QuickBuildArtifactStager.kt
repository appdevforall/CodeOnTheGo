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
 * Extracts the quick-build artifacts from APK assets to `<ANDROIDIDE_HOME>/quickbuild/` - the
 * runtime AAR, and the daemon zip unpacked into `daemon/` (the daemon jar plus the runtime
 * classpath its manifest Class-Path names).
 *
 * The AAR is copied on every call. The daemon directory is wiped and re-extracted only when
 * the installed APK changed: a stamp file, written last so a crash mid-extract leaves none,
 * records the package's versionCode and lastUpdateTime - not a version constant, which would
 * serve a stale bundle when content changes without a bump; any install, an unchanged-version
 * reinstall included, moves lastUpdateTime. That saves a 62 MB extraction per provision and
 * rebaseline. It also keeps the one wipe path away from a live compile daemon, which loads
 * the jars under `daemon/` lazily: the wipe runs only after an APK update, which force-stops
 * the app and its child processes. No known path stages while a daemon is alive anyway - a
 * rebaseline shuts it down before the Gradle build runs - so this is a guard, not a fix.
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

	/** Identity of the installed APK; see the class doc for why lastUpdateTime and not a constant. */
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
	 * [installStamp] - the stamp file matches and [daemonJar] is present. Internal so the JVM
	 * test can watch the skip, and the wipe, without an Android [Context].
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
		if (daemonJar.isFile && stamp.isFile && stamp.readText() == installStamp) {
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
