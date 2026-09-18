package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.itsaky.androidide.assets.JarLinks
import com.itsaky.androidide.utils.Environment
import org.adfa.constants.GRADLE_DISTRIBUTION_NAME
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
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
	 * @param gradleDists passed through to [linkDistJars]; parameterised only so tests can point
	 *   at a fake distribution, since [Environment.GRADLE_DISTS] is unset off-device.
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
		// Before the stamp: the skip test above reads the stamp and the daemon jar, not the
		// linked jars, so stamping a staging whose link failed would skip re-staging forever
		// and leave the daemon without a compiler until the next install.
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
	 * Put the jars the APK deliberately does not carry where the daemon jar's manifest
	 * Class-Path expects them.
	 *
	 * ADFA-4931: the on-device Gradle distribution already ships these artifacts at the same
	 * versions, byte for byte - the compiler alone is ~57 MB - so a second copy in the daemon
	 * zip was pure duplication. `gradle-dists/` and `quickbuild/` are always on one filesystem
	 * (both under `<ANDROIDIDE_HOME>`), so a symlink costs nothing; a copy is the fallback for
	 * a filesystem that refuses one.
	 *
	 * The names come from [LINKED_JARS_LIST], staged into the daemon dir by :app's
	 * `quickBuildDistLinkedJarList`, so the build's exclusion list and this link step cannot
	 * drift apart.
	 *
	 * @param gradleDists parameterised only so tests can point at a fake distribution; production
	 *   callers take the default.
	 * @throws FileNotFoundException when the list is absent, or when the distribution has not
	 *   been extracted yet. There is deliberately no bundled fallback: the alternative to
	 *   failing here is a NoClassDefFoundError partway into the user's first compile, which is
	 *   far harder to read.
	 */
	@Throws(IOException::class)
	internal fun linkDistJars(
		daemonDir: File,
		gradleDists: File = Environment.GRADLE_DISTS,
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
			if (JarLinks.trySymlink(target, source)) {
				log.info("Linked {} -> {}", target, source)
			} else {
				// The daemon has to have these bytes one way or another, so a filesystem that
				// refuses links costs us the copy.
				log.info("Copying {} into the daemon dir instead", source)
				source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
			}
		}
	}

	/**
	 * The jar names the build left out of the zip for the distribution to supply. A missing or
	 * empty list means the zip was built without the staging task, which would otherwise show up
	 * as a first compile failing on a jar nobody linked.
	 */
	private fun readLinkedJarNames(daemonDir: File): List<String> {
		val list = File(daemonDir, LINKED_JARS_LIST)
		if (!list.isFile) {
			throw FileNotFoundException(
				"$LINKED_JARS_LIST missing from the staged daemon at $daemonDir. It names the jars " +
					"Quick Build links from the Gradle distribution rather than shipping in the APK.",
			)
		}
		val names = list.readLines().map(String::trim).filter(String::isNotEmpty)
		if (names.isEmpty()) {
			throw FileNotFoundException("$LINKED_JARS_LIST at $list names no jars")
		}
		return names
	}
}
