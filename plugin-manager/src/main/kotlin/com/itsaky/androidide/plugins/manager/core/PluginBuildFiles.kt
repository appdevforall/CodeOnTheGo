package com.itsaky.androidide.plugins.manager.core

import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The files of installed plugin builds: the plugin files in [pluginsDir], and the native libraries
 * extracted from them under [nativeLibsDir].
 *
 * Every build gets paths of its own. Android caches a plugin's resources and assets by file path,
 * and refuses to open a native library at a path another class loader already opened, so a build
 * installed at the previous build's paths runs against the previous build's files until the app
 * restarts. Plugin files are named `<pluginId>-<hash>.cgp`, after their content. A file installed
 * before that, `<pluginId>.cgp`, keeps loading until its plugin is next updated.
 */
class PluginBuildFiles internal constructor(
	private val pluginsDir: File,
	private val nativeLibsDir: File,
	private val readPluginId: (File) -> String?,
) {
	/** Every plugin file whose manifest has [pluginId]. Its name can't tell: it names the build. */
	fun filesOf(pluginId: String): List<File> =
		pluginsDir
			.listFiles { file -> file.isFile && file.name.endsWith(".$PLUGIN_ARCHIVE_EXTENSION", ignoreCase = true) }
			.orEmpty()
			.filter { file ->
				runCatching { readPluginId(file) }
					.onFailure { log.warn("Could not read the plugin ID of {}", file.name, it) }
					.getOrNull() == pluginId
			}

	/**
	 * Deletes every file of [pluginId], not just the first, since two would both load. True when
	 * one was deleted. The plugin's native libraries go even when it has no file left: an install
	 * whose load failed deletes its file but not the libraries it extracted.
	 */
	fun delete(pluginId: String): Boolean {
		File(nativeLibsDir, pluginId).deleteRecursively()
		val files = filesOf(pluginId)
		val undeleted = files.filterNot(File::delete)
		undeleted.forEach { log.error("Could not delete plugin file {}", it.absolutePath) }
		return undeleted.size < files.size
	}

	/**
	 * Extracts [build]'s native libraries for [abi] into a folder of that build's own, and deletes
	 * the folders of [pluginId]'s other builds. Null when [build] has none for [abi].
	 */
	fun extractNativeLibs(
		pluginId: String,
		build: File,
		abi: String,
	): File? {
		val pluginDir = File(nativeLibsDir, pluginId)
		val buildDir = File(pluginDir, buildKey(pluginId, build))
		if (!buildDir.isDirectory && !extractNativeLibs(build, "lib/$abi/", buildDir)) {
			pluginDir.deleteRecursively()
			return null
		}
		pluginDir.listFiles { file -> file != buildDir }?.forEach(File::deleteRecursively)
		return buildDir
	}

	/** Extracts into a scratch folder renamed when complete, so an interrupted extraction is never reused. */
	private fun extractNativeLibs(
		build: File,
		libPrefix: String,
		buildDir: File,
	): Boolean {
		val scratch = File(buildDir.parentFile, ".${buildDir.name}.partial")
		scratch.deleteRecursively()
		scratch.mkdirs()
		val targetPath = scratch.toPath().toAbsolutePath().normalize()

		var found = false
		ZipFile(build).use { zip ->
			zip
				.entries()
				.asSequence()
				.filter { !it.isDirectory && it.name.startsWith(libPrefix) && it.name.endsWith(".so") }
				.forEach { entry ->
					val outPath = targetPath.resolve(entry.name.substringAfterLast("/")).normalize()
					if (outPath.startsWith(targetPath)) {
						zip.getInputStream(entry).use { input ->
							outPath.toFile().outputStream().use { output ->
								input.copyTo(output)
							}
						}
						outPath.toFile().setExecutable(true, false)
						found = true
					}
				}
		}

		if (!found) {
			scratch.deleteRecursively()
			return false
		}
		if (!scratch.renameTo(buildDir)) {
			scratch.deleteRecursively()
			throw IOException("Could not move native libraries into ${buildDir.absolutePath}")
		}
		return true
	}

	companion object {
		private val log = LoggerFactory.getLogger(PluginBuildFiles::class.java)

		private const val HASH_CHARS = 16

		/** The name, without extension, to install [build] under: `<pluginId>-<hash of its content>`. */
		fun fileStem(
			pluginId: String,
			build: File,
		): String = "$pluginId-${sha256Hex(build).take(HASH_CHARS)}"

		/** The hash in [installed]'s name, or its whole name for a `<pluginId>.cgp` installed before hashes. */
		internal fun buildKey(
			pluginId: String,
			installed: File,
		): String = installed.nameWithoutExtension.removePrefix("$pluginId-")

		private fun sha256Hex(file: File): String {
			val digest = MessageDigest.getInstance("SHA-256")
			file.inputStream().use { input ->
				val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
				while (true) {
					val read = input.read(buffer)
					if (read < 0) break
					digest.update(buffer, 0, read)
				}
			}
			return digest.digest().joinToString("") { "%02x".format(it) }
		}
	}
}
