package com.itsaky.androidide.quickbuild

import android.content.Context
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Extracts the quick-build artifacts from APK assets to
 * `<ANDROIDIDE_HOME>/quickbuild/` (the LogSender-AAR pattern):
 *
 * - `data/common/quickbuild-runtime.aar` -> `quickbuild/quickbuild-runtime.aar`
 * - `data/common/quickbuild-daemon.zip`  -> unzipped into `quickbuild/daemon/`
 *   (daemon jar + its full runtime classpath; the jar's manifest Class-Path names the
 *   sibling jars)
 *
 * Runs on EVERY provision rather than behind a version marker: a marker keyed on a
 * version constant silently serves a stale bundle when content changes without a bump
 * (a recurring CoGo bug shape); re-extracting a few MB once per session is cheap.
 */
object QuickBuildArtifactStager {
	private val log = LoggerFactory.getLogger(QuickBuildArtifactStager::class.java)

	private const val ASSET_RUNTIME_AAR = "data/common/quickbuild-runtime.aar"
	private const val ASSET_DAEMON_ZIP = "data/common/quickbuild-daemon.zip"

	/** @throws IOException when an asset is missing or extraction fails. */
	@Throws(IOException::class)
	fun stage(
		context: Context,
		paths: EnvironmentQuickBuildPaths,
	) {
		stageRuntimeAar(context, paths.runtimeAar)
		stageDaemon(context, paths.daemonDir)
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

	private fun stageDaemon(
		context: Context,
		daemonDir: File,
	) {
		if (daemonDir.exists()) {
			daemonDir.deleteRecursively()
		}
		Environment.mkdirIfNotExists(daemonDir)

		val canonicalRoot = daemonDir.canonicalFile
		ZipInputStream(context.assets.open(ASSET_DAEMON_ZIP).buffered()).use { zip ->
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
			log.info("Staged {} daemon files into {}", count, daemonDir)
		}
	}
}
