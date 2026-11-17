package com.itsaky.androidide.plugins.manager.core

import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * The files of installed plugin builds: the plugin files in [pluginsDir], and the native libraries
 * extracted from them under [nativeLibsDir].
 *
 * Every build gets paths of its own. Android caches a plugin's resources and assets by file path,
 * and refuses to open a native library at a path another class loader already opened, so a build
 * installed at the previous build's paths runs against the previous build's files until the app
 * restarts. Plugin files are named `<pluginId>-<hash>.cgp`, after their content. A file installed
 * before that, `<pluginId>.cgp`, keeps loading until its plugin is next updated. An older CoGo
 * build updates that file in place, so its libraries' folder is named after its size and time.
 *
 * [legacyNativeLibsDir] is where CoGo builds before per-build folders extracted libraries. It is
 * deleted, never read.
 */
class PluginBuildFiles internal constructor(
	private val pluginsDir: File,
	private val nativeLibsDir: File,
	private val legacyNativeLibsDir: File? = null,
	private val readPluginId: (File) -> String?,
) {
	private val legacyDeleted = AtomicBoolean(legacyNativeLibsDir == null)

	// One extraction or deletion per plugin at a time: loads can overlap, and two extractions of a
	// build would share its scratch folder.
	private val locks = ConcurrentHashMap<String, Any>()

	// Native-library folders handed to a class loader in this process. A folder deleted by an
	// uninstall is never handed out again under the same path: the runtime would refuse to open a
	// library there for the reinstalled build's class loader while the old one still holds it.
	private val handedOut: MutableSet<String> = ConcurrentHashMap.newKeySet()

	/** Whether [file] is a plugin file, which only its manifest ties to a plugin. */
	private fun isPluginFile(file: File): Boolean =
		file.isFile && file.parentFile == pluginsDir && file.name.endsWith(".$PLUGIN_ARCHIVE_EXTENSION", ignoreCase = true)

	/**
	 * Whether [file] is ART's compiled code for a file of [pluginId] that is gone. Call it after
	 * [delete], which removed all of [pluginId]'s plugin files. The name can't tell the plugin: an
	 * older CoGo build's `<id>.cgp` may have an ID that looks like `<pluginId>-<hash>`. So a plugin
	 * file left under the stem belongs to another plugin, and its compiled code is kept.
	 */
	fun isLeftoverCompiledCodeOf(
		pluginId: String,
		file: File,
	): Boolean {
		if (!isCompiledCodeOf(pluginId, file)) return false
		val stem = file.name.replace(Regex("\\.(odex|vdex|art|$PLUGIN_ARCHIVE_EXTENSION\\.(cur\\.)?prof)$"), "")
		// loadPlugins loads .CGP too, so any case of the extension keeps the code.
		return pluginsDir.listFiles { f -> isPluginFile(f) && f.nameWithoutExtension == stem }.isNullOrEmpty()
	}

	/** Every plugin file whose manifest has [pluginId]. Its name can't tell: it names the build. */
	fun filesOf(pluginId: String): List<File> =
		pluginsDir
			.listFiles(::isPluginFile)
			.orEmpty()
			.filter { file ->
				runCatching { readPluginId(file) }
					.onFailure { log.warn("Could not read the plugin ID of {}", file.name, it) }
					.getOrNull() == pluginId
			}

	/**
	 * Deletes every file of [pluginId], not just the first, since two would both load. True when
	 * there was one and all were deleted: a file left behind loads again on the next start. The
	 * plugin's native libraries go even when it has no file left: an install whose load failed
	 * deletes its file but not the libraries it extracted. They don't count toward the result: with
	 * its files gone the plugin is gone, and its next install deletes the folders of other builds.
	 */
	fun delete(pluginId: String): Boolean =
		synchronized(lockFor(pluginId)) {
			deleteLegacyNativeLibs()
			if (!pluginDirOf(pluginId).deleteWithoutFollowingLinks()) {
				log.error("Could not delete all native libraries of {}", pluginId)
			}
			val files = filesOf(pluginId)
			val undeleted = files.filterNot(File::delete)
			undeleted.forEach { log.error("Could not delete plugin file {}", it.absolutePath) }
			files.isNotEmpty() && undeleted.isEmpty()
		}

	/**
	 * Extracts [build]'s native libraries for [abi] into a folder of that build's own, and deletes
	 * [pluginId]'s folders that belong to no installed build. Null when [build] has none for [abi].
	 */
	fun extractNativeLibs(
		pluginId: String,
		build: File,
		abi: String,
	): File? =
		synchronized(lockFor(pluginId)) {
			deleteLegacyNativeLibs()
			val pluginDir = pluginDirOf(pluginId)
			if (Files.isSymbolicLink(pluginDir.toPath())) {
				log.warn("{} is a link; deleting the link and extracting into a folder", pluginDir)
				pluginDir.deleteWithoutFollowingLinks()
				// Else the steps below run inside the link's target and empty it.
				if (Files.isSymbolicLink(pluginDir.toPath())) throw IOException("Could not delete the link ${pluginDir.absolutePath}")
			}
			val key = buildKey(pluginId, build)
			// Before folderFor: a link left at the build's path would block the scratch folder's rename.
			deleteLinksIn(pluginDir)
			val buildDir = folderFor(pluginDir, key)
			val extracted = isRealDirectory(buildDir) || extractNativeLibs(build, "lib/$abi/", buildDir)
			// Another installed build of this plugin keeps its folder: it may be loading from it.
			val otherBuilds = installedKeys(pluginId) - key
			pluginDir
				.listFiles { file -> file != buildDir && !(isRealDirectory(file) && file.name.substringBefore('~') in otherBuilds) }
				?.forEach(File::deleteWithoutFollowingLinks)
			pluginDir.delete() // only when empty
			if (extracted) buildDir.also { handedOut += it.absolutePath } else null
		}

	private fun lockFor(pluginId: String): Any = locks.computeIfAbsent(pluginId) { Any() }

	private fun deleteLegacyNativeLibs() {
		if (legacyDeleted.compareAndSet(false, true) && legacyNativeLibsDir?.deleteWithoutFollowingLinks() == false) {
			log.warn("Could not delete {}", legacyNativeLibsDir)
		}
	}

	/**
	 * [pluginId]'s folder of native libraries. Throws unless that is a folder of [nativeLibsDir]
	 * named [pluginId]: an ID that is a path would have it delete a folder elsewhere. Compares
	 * normalized paths and resolves no link, so a link at that path is returned as it is, for the
	 * caller to delete as a link.
	 */
	private fun pluginDirOf(pluginId: String): File {
		val root = nativeLibsDir.toPath().toAbsolutePath().normalize()
		val dir = root.resolve(pluginId).normalize()
		require("\\" !in pluginId && dir.parent == root && dir.fileName?.toString() == pluginId) {
			"Plugin ID \"$pluginId\" names no folder of ${nativeLibsDir.absolutePath}"
		}
		return dir.toFile()
	}

	// A link is never a folder of ours: plugin code can plant one, and it would be followed.
	private fun isRealDirectory(file: File): Boolean = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

	private fun deleteLinksIn(pluginDir: File) {
		pluginDir.listFiles { file -> Files.isSymbolicLink(file.toPath()) }?.forEach {
			log.warn("{} is a link; deleting the link", it)
			it.deleteWithoutFollowingLinks()
		}
	}

	/**
	 * The build's folder: `<key>`, or `<key>~<n>` when an uninstall in this process deleted the
	 * folders it handed out before. An existing one is reused.
	 */
	private fun folderFor(
		pluginDir: File,
		key: String,
	): File {
		val existing = pluginDir.listFiles { file -> isRealDirectory(file) && (file.name == key || file.name.startsWith("$key~")) }
		existing?.minByOrNull { it.name.length }?.let { return it }
		return generateSequence(0) { it + 1 }
			.map { n -> File(pluginDir, if (n == 0) key else "$key~$n") }
			.first { it.absolutePath !in handedOut }
	}

	/**
	 * The build keys of [pluginId]'s files, by name, which is cheap enough for every load. A file of
	 * another plugin whose ID starts with `<pluginId>-` can match too; that only keeps a folder.
	 */
	private fun installedKeys(pluginId: String): Set<String> =
		pluginsDir
			.listFiles { file ->
				file.isFile &&
					file.name.endsWith(".$PLUGIN_ARCHIVE_EXTENSION", ignoreCase = true) &&
					(file.nameWithoutExtension == pluginId || file.nameWithoutExtension.startsWith("$pluginId-"))
			}.orEmpty()
			.mapTo(HashSet()) { buildKey(pluginId, it) }

	/** Extracts into a scratch folder renamed when complete, so an interrupted extraction is never reused. */
	private fun extractNativeLibs(
		build: File,
		libPrefix: String,
		buildDir: File,
	): Boolean {
		val scratch = File(buildDir.parentFile, ".${buildDir.name}.partial")
		scratch.deleteWithoutFollowingLinks()
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
			scratch.deleteWithoutFollowingLinks()
			return false
		}
		if (!scratch.renameTo(buildDir)) {
			scratch.deleteWithoutFollowingLinks()
			throw IOException("Could not move native libraries into ${buildDir.absolutePath}")
		}
		return true
	}

	companion object {
		private val log = LoggerFactory.getLogger(PluginBuildFiles::class.java)

		private const val HASH_CHARS = 16

		// Not app_plugin_native_libs: an older CoGo build reuses its plugin's folder there whatever it
		// holds, so it would find these per-build folders and no libraries. Folder names as getDir()
		// makes them.
		private const val NATIVE_LIBS_DIR = "app_plugin_build_libs"
		private const val LEGACY_NATIVE_LIBS_DIR = "app_plugin_native_libs"

		/** The plugin build files of the app whose data folder is [dataDir]. */
		internal fun inDataDir(
			pluginsDir: File,
			dataDir: File,
			readPluginId: (File) -> String?,
		) = PluginBuildFiles(pluginsDir, File(dataDir, NATIVE_LIBS_DIR), File(dataDir, LEGACY_NATIVE_LIBS_DIR), readPluginId)

		/** The name, without extension, to install [build] under: `<pluginId>-<hash of its content>`. */
		fun fileStem(
			pluginId: String,
			build: File,
		): String = "$pluginId-${sha256Hex(build).take(HASH_CHARS)}"

		/**
		 * The name of [installed]'s library folder: the hash in its name. A `<pluginId>.cgp` installed
		 * before hashes gets `legacy-<size>-<modified time>`, since an older CoGo build rewrites it in
		 * place. Its name alone would keep the previous content's libraries.
		 */
		internal fun buildKey(
			pluginId: String,
			installed: File,
		): String =
			if (installed.nameWithoutExtension == pluginId) {
				"legacy-${installed.length()}-${installed.lastModified()}"
			} else {
				installed.nameWithoutExtension.removePrefix("$pluginId-")
			}

		/**
		 * Whether [file] is ART's compiled code for a file of [pluginId]: `<stem>.odex`, `.vdex` or
		 * `.art`, or `<stem>.cgp.prof` or `.cgp.cur.prof`. Matched exactly, since another plugin's ID
		 * or content hash can contain this ID. By name only: see [isLeftoverCompiledCodeOf].
		 */
		internal fun isCompiledCodeOf(
			pluginId: String,
			file: File,
		): Boolean =
			file.isFile &&
				Regex("${Regex.escape(pluginId)}(-[0-9a-f]{$HASH_CHARS})?\\.(odex|vdex|art|$PLUGIN_ARCHIVE_EXTENSION\\.(cur\\.)?prof)")
					.matches(file.name)

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
