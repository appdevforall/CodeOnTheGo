package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.itsaky.androidide.utils.Environment
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
	 * @return whether an extraction ran.
	 */
	@Throws(IOException::class)
	internal fun stageDaemonIfNeeded(
		installStamp: String,
		daemonDir: File,
		daemonJar: File,
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
}
