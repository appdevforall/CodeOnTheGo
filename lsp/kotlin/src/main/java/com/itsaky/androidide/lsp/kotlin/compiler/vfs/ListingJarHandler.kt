package com.itsaky.androidide.lsp.kotlin.compiler.vfs

import com.itsaky.androidide.zipfs2.listing.JarListing
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipException

private val logger = LoggerFactory.getLogger(ListingJarHandler::class.java)

/**
 * Serves the entries of one JAR, as listed by [snapshot], to [fileSystem], and keeps the
 * [ListingJarVirtualFile]s it has created for as long as something references them.
 *
 * Entry contents are read through one file handle per handler, opened on the first read and
 * closed by [drop]. The open handle keeps reading the file the listing was built from even if the
 * JAR is replaced by a rename.
 */
internal class ListingJarHandler(
	val fileSystem: ListingJarFileSystem,
	val jarPath: String,
	val snapshot: JarListingCache.Snapshot,
) {
	val listing: JarListing = snapshot.listing()

	/** The JAR's modification time in milliseconds, which `CoreJarVirtualFile` reports for every file entry. */
	val jarTimeStamp: Long = Math.floorDiv(snapshot.modifiedNanos(), NANOS_PER_MILLI)

	private val files = ContainerUtil.createIntKeyWeakValueMap<ListingJarVirtualFile>()

	private val jarLock = Any()

	/** Guarded by [jarLock]; a `RandomAccessFile`, unlike a `FileChannel`, is not closed by a thread interrupt. */
	private var jar: RandomAccessFile? = null

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
	 * Reads the uncompressed bytes of the entry at [index] from the JAR.
	 *
	 * @throws IOException if this handler was dropped, the JAR on disk no longer matches the
	 *   listing, or the entry cannot be read
	 */
	fun contentsOf(index: Int): ByteArray {
		if (listing.isDirectory(index)) throw FileNotFoundException("$jarPath!${listing.name(index)} is a directory")
		val size = listing.size(index)
		if (size > MAX_ENTRY_SIZE) throw ZipException("$jarPath!${listing.name(index)} is too large: $size bytes")
		return when (val method = listing.method(index)) {
			METHOD_STORED -> {
				readCompressed(index, extra = 0)
			}

			METHOD_DEFLATED -> {
				// The output array is sized from metadata, so refuse a size no deflate stream of this length can produce.
				if (size > (listing.compressedSize(index) + 1) * MAX_DEFLATE_RATIO) {
					throw ZipException("$jarPath!${listing.name(index)}: $size bytes cannot inflate from ${listing.compressedSize(index)}")
				}
				inflate(readCompressed(index, extra = 1), size.toInt(), index)
			}

			else -> {
				throw ZipException("$jarPath!${listing.name(index)} has unsupported method $method")
			}
		}
	}

	/**
	 * Marks every file of this handler invalid and closes its file handle; called once the JAR's
	 * listing is dropped.
	 *
	 * Created files stay cached, so a file reached from a dead one is still the same object.
	 */
	fun drop() {
		synchronized(jarLock) {
			isValid = false
			try {
				jar?.close()
			} catch (e: IOException) {
				logger.warn("Cannot close JAR {}", jarPath, e)
			}
			jar = null
		}
	}

	/** Returns how many created files are still referenced. */
	fun createdFileCount(): Int = synchronized(files) { files.size() }

	private fun create(index: Int): ListingJarVirtualFile {
		val parent = listing.parent(index)
		return ListingJarVirtualFile(this, index, if (parent < 0) null else fileAt(parent))
	}

	/** Reads the entry's raw data, followed by [extra] zero bytes, after checking its local header. */
	private fun readCompressed(
		index: Int,
		extra: Int,
	): ByteArray {
		val compressedSize = listing.compressedSize(index)
		if (compressedSize > MAX_ENTRY_SIZE) throw ZipException("$jarPath!${listing.name(index)} is too large: $compressedSize bytes")
		// Checked before allocating, so a corrupt central directory cannot claim more heap than the file holds.
		if (listing.localHeaderOffset(index) + LOC_HEADER_SIZE + compressedSize > snapshot.size()) {
			throw ZipException("$jarPath!${listing.name(index)}: $compressedSize bytes run past the end of the JAR")
		}
		val data = ByteArray(compressedSize.toInt() + extra)
		val header = ByteArray(LOC_HEADER_SIZE)
		synchronized(jarLock) {
			val file = openLocked()
			val offset = listing.localHeaderOffset(index)
			file.seek(offset)
			file.readFully(header)
			if (u32(header, 0) != LOC_SIGNATURE) throw ZipException("$jarPath!${listing.name(index)}: bad local header")
			val name = ByteArray(u16(header, LOC_NAME_LENGTH))
			file.readFully(name)
			if (!namesMatch(name, index)) throw IOException("$jarPath changed on disk: ${listing.name(index)} is not at its listed offset")
			file.seek(offset + LOC_HEADER_SIZE + name.size + u16(header, LOC_EXTRA_LENGTH))
			file.readFully(data, 0, compressedSize.toInt())
		}
		return data
	}

	/** Returns whether a local header's raw [name] is the listed name of the entry at [index]. */
	private fun namesMatch(
		name: ByteArray,
		index: Int,
	): Boolean {
		val listed = listing.nameBytes(index)
		if (name.size == listed.size - 1 && name.contentEquals(listed.copyOfRange(1, listed.size))) return true
		// Listed names are normalized; compare a raw name with odd slashes the same way.
		return "/" +
			name
				.decodeToString()
				.split('/')
				.filter { it.isNotEmpty() }
				.joinToString("/") == listing.name(index)
	}

	private fun openLocked(): RandomAccessFile {
		if (!isValid) throw IOException("$jarPath was replaced on disk")
		jar?.let { return it }
		val opened = RandomAccessFile(jarPath, "r")
		try {
			if (opened.length() != snapshot.size()) throw IOException("$jarPath changed on disk since it was listed")
		} catch (e: IOException) {
			opened.close()
			throw e
		}
		jar = opened
		return opened
	}

	/** Inflates [compressed], whose trailing zero byte is the dummy input a nowrap `Inflater` may need. */
	private fun inflate(
		compressed: ByteArray,
		size: Int,
		index: Int,
	): ByteArray {
		val out = ByteArray(size)
		val inflater = Inflater(true)
		try {
			inflater.setInput(compressed)
			var read = 0
			while (read < size) {
				val n =
					try {
						inflater.inflate(out, read, size - read)
					} catch (e: DataFormatException) {
						throw ZipException("$jarPath!${listing.name(index)}: corrupt deflate data").apply { initCause(e) }
					}
				if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
				read += n
			}
			if (read != size) throw ZipException("$jarPath!${listing.name(index)}: inflated $read of $size bytes")
		} finally {
			inflater.end()
		}
		return out
	}

	private fun normalize(entryPath: String): String = "/" + entryPath.split('/').filter { it.isNotEmpty() }.joinToString("/")

	private companion object {
		const val ROOT = 0
		const val NANOS_PER_MILLI = 1_000_000L
		const val MAX_ENTRY_SIZE = Int.MAX_VALUE - 16L

		// Deflate's upper bound: a 258-byte match costs at least 2 bits (1-bit length and distance codes).
		const val MAX_DEFLATE_RATIO = 1032L
		const val METHOD_STORED = 0
		const val METHOD_DEFLATED = 8
		const val LOC_SIGNATURE = 0x04034b50L
		const val LOC_HEADER_SIZE = 30
		const val LOC_NAME_LENGTH = 26
		const val LOC_EXTRA_LENGTH = 28

		fun u16(
			bytes: ByteArray,
			at: Int,
		): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

		fun u32(
			bytes: ByteArray,
			at: Int,
		): Long = (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)
	}
}
