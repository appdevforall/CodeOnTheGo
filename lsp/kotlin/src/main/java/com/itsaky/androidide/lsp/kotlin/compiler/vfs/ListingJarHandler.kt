package com.itsaky.androidide.lsp.kotlin.compiler.vfs

import com.itsaky.androidide.zipfs2.listing.JarListing
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Serves the entries of one JAR, as listed by [listing], to [fileSystem], and keeps the
 * [ListingJarVirtualFile]s it has created for as long as something references them.
 */
internal class ListingJarHandler(
	val fileSystem: ListingJarFileSystem,
	val jarPath: String,
	val listing: JarListing,
	val jarTimeStamp: Long,
) {
	private val files = ContainerUtil.createIntKeyWeakValueMap<ListingJarVirtualFile>()

	@Volatile
	var isValid = true
		private set

	/** Returns the file for [entryPath], relative to the JAR root, or null if there is no such entry. */
	fun findFile(entryPath: String): ListingJarVirtualFile? {
		val name = normalize(entryPath)
		val index = if (name == "/") ROOT else listing.find(name)
		return if (index < 0) null else fileAt(index)
	}

	/** Returns the child of the directory at [dir] named [name], or null if it has none. */
	fun findChild(
		dir: Int,
		name: String,
	): ListingJarVirtualFile? {
		if (name.isEmpty() || '/' in name) return null
		val prefix = if (dir == ROOT) "" else listing.name(dir)
		val index = listing.find("$prefix/$name")
		return if (index < 0) null else fileAt(index)
	}

	/** Returns the files for the children of the directory at [dir]. */
	fun childrenOf(dir: Int): List<ListingJarVirtualFile> {
		val children = mutableListOf<ListingJarVirtualFile>()
		var child = listing.firstChild(dir)
		while (child != -1) {
			children.add(fileAt(child))
			child = listing.nextSibling(child)
		}
		return children
	}

	/** Returns the file for the entry at [index], creating it and its missing parents if needed. */
	fun fileAt(index: Int): ListingJarVirtualFile =
		synchronized(files) {
			files[index] ?: create(index).also { files.put(index, it) }
		}

	/**
	 * Reads the uncompressed bytes of the entry at [index] from the JAR on disk.
	 *
	 * @throws IOException if the JAR changed since this handler was created, or the entry cannot be read
	 */
	fun contentsOf(index: Int): ByteArray {
		if (!isValid) throw IOException("$jarPath was replaced on disk")
		val entryName = listing.name(index).substring(1)
		if (listing.isDirectory(index)) throw FileNotFoundException("$jarPath!/$entryName is a directory")
		ZipFile(jarPath).use { zip ->
			val entry = zip.getEntry(entryName) ?: throw FileNotFoundException("$jarPath!/$entryName")
			return zip.getInputStream(entry).use { it.readBytes() }
		}
	}

	/**
	 * Marks every file of this handler invalid; called once the JAR's listing is dropped.
	 *
	 * Created files stay cached, so a file reached from a dead one is still the same object.
	 */
	fun drop() {
		isValid = false
	}

	/** Returns how many created files are still referenced. */
	fun createdFileCount(): Int = synchronized(files) { files.size() }

	private fun create(index: Int): ListingJarVirtualFile {
		val parent = listing.parent(index)
		return ListingJarVirtualFile(this, index, if (parent < 0) null else fileAt(parent))
	}

	private fun normalize(entryPath: String): String = "/" + entryPath.split('/').filter { it.isNotEmpty() }.joinToString("/")

	private companion object {
		const val ROOT = 0
	}
}
