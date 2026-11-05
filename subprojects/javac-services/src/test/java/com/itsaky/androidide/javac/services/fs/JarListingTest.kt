/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.javac.services.fs

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.zipfs2.listing.JarListing
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** [JarListing] must expose the same entries and metadata as the JAR's central directory. */
class JarListingTest {
	private lateinit var tmpDir: File

	@Before
	fun setUp() {
		tmpDir =
			File.createTempFile("jar-listing-test", "").apply {
				delete()
				mkdirs()
			}
	}

	@After
	fun tearDown() {
		tmpDir.deleteRecursively()
	}

	private fun zip(
		name: String = "fixture.jar",
		block: ZipOutputStream.() -> Unit,
	): File {
		val file = File(tmpDir, name)
		ZipOutputStream(file.outputStream()).use(block)
		return file
	}

	private fun ZipOutputStream.add(
		name: String,
		content: String = "",
	) {
		putNextEntry(ZipEntry(name))
		write(content.toByteArray())
		closeEntry()
	}

	private fun ZipOutputStream.addStored(
		name: String,
		content: String,
	) {
		val bytes = content.toByteArray()
		val entry =
			ZipEntry(name).apply {
				method = ZipEntry.STORED
				size = bytes.size.toLong()
				compressedSize = bytes.size.toLong()
				crc = CRC32().apply { update(bytes) }.value
			}
		putNextEntry(entry)
		write(bytes)
		closeEntry()
	}

	private fun fixture(): File =
		zip {
			add("a/")
			add("a/b/")
			add("a/b/C.class", "class-bytes-class-bytes-class-bytes")
			add("a/D.class", "d")
			add("x/y/z/E.class", "no directory entries for x, x/y, x/y/z")
			add("META-INF/versions/9/p/F.class", "multi-release")
			add("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
			add("/leading/G.txt", "leading slash")
			addStored("stored.txt", "stored content stored content")
			add("a//b/H.txt", "doubled slash")
		}

	private fun children(
		listing: JarListing,
		dir: String,
	): Set<String> {
		val result = mutableSetOf<String>()
		var child = listing.firstChild(listing.find(dir))
		while (child != -1) {
			result += listing.shortName(child)
			child = listing.nextSibling(child)
		}
		return result
	}

	@Test
	fun `every entry is found with zip metadata equal to ZipFile's`() {
		val jar = fixture()
		val listing = JarListing.read(jar.toPath())

		ZipFile(jar).use { zf ->
			for (entry in zf.entries().asSequence()) {
				val normalized = normalize(entry.name)
				val i = listing.find(normalized)
				assertThat(i).isAtLeast(0)
				assertThat(listing.crc(i)).isEqualTo(entry.crc)
				assertThat(listing.size(i)).isEqualTo(entry.size)
				assertThat(listing.compressedSize(i)).isEqualTo(entry.compressedSize)
				assertThat(listing.method(i)).isEqualTo(entry.method)
				assertThat(listing.isDirectory(i)).isEqualTo(entry.isDirectory)
			}
		}
	}

	@Test
	fun `stored entries keep the stored method`() {
		val listing = JarListing.read(fixture().toPath())

		assertThat(listing.method(listing.find("/stored.txt"))).isEqualTo(ZipEntry.STORED)
	}

	@Test
	fun `local header offsets point at local header signatures`() {
		val jar = fixture()
		val listing = JarListing.read(jar.toPath())

		val bytes = jar.readBytes()
		val i = listing.find("/a/D.class")
		val offset = listing.localHeaderOffset(i).toInt()
		assertThat(bytes.copyOfRange(offset, offset + 4))
			.isEqualTo(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
	}

	@Test
	fun `missing parent directories are synthesized`() {
		val listing = JarListing.read(fixture().toPath())

		val z = listing.find("/x/y/z")
		assertThat(z).isAtLeast(0)
		assertThat(listing.isDirectory(z)).isTrue()
		assertThat(listing.localHeaderOffset(z)).isEqualTo(-1L)
		assertThat(listing.size(z)).isEqualTo(0L)
		assertThat(listing.compressedSize(z)).isEqualTo(0L)

		val chain = mutableListOf<String>()
		var i = z
		while (i != -1) {
			chain += listing.name(i)
			i = listing.parent(i)
		}
		assertThat(chain).containsExactly("/x/y/z", "/x/y", "/x", "/").inOrder()
	}

	@Test
	fun `root is index 0 and has no parent`() {
		val listing = JarListing.read(fixture().toPath())

		assertThat(listing.find("/")).isEqualTo(0)
		assertThat(listing.name(0)).isEqualTo("/")
		assertThat(listing.parent(0)).isEqualTo(-1)
		assertThat(listing.isDirectory(0)).isTrue()
	}

	@Test
	fun `children of a directory are exactly its entries`() {
		val listing = JarListing.read(fixture().toPath())

		assertThat(children(listing, "/"))
			.containsExactly("a", "x", "META-INF", "leading", "stored.txt")
		assertThat(children(listing, "/a")).containsExactly("b", "D.class")
		assertThat(children(listing, "/a/b")).containsExactly("C.class", "H.txt")
		assertThat(children(listing, "/META-INF/versions/9")).containsExactly("p")
	}

	@Test
	fun `a file has no children`() {
		val listing = JarListing.read(fixture().toPath())

		assertThat(listing.firstChild(listing.find("/a/D.class"))).isEqualTo(-1)
	}

	@Test
	fun `absent names return -1`() {
		val listing = JarListing.read(fixture().toPath())

		assertThat(listing.find("/nope")).isEqualTo(-1)
		assertThat(listing.find("/a/b/C.class/x")).isEqualTo(-1)
		assertThat(listing.find("/a/")).isEqualTo(-1)
		assertThat(listing.find("a")).isEqualTo(-1)
		assertThat(listing.find("/nope".toByteArray())).isEqualTo(-1)
	}

	@Test
	fun `names are normalized like zipfs2 IndexNode`() {
		val listing = JarListing.read(fixture().toPath())

		assertThat(listing.find("/a/b/H.txt")).isAtLeast(0)
		assertThat(listing.find("/leading/G.txt")).isAtLeast(0)
		assertThat(listing.name(listing.find("/a/b"))).isEqualTo("/a/b")
	}

	@Test
	fun `a doubled slash directory entry is normalized`() {
		val jar = zip { add("a//b/") }
		val listing = JarListing.read(jar.toPath())

		val b = listing.find("/a/b")
		assertThat(b).isAtLeast(0)
		assertThat(listing.isDirectory(b)).isTrue()
		assertThat(listing.size()).isEqualTo(3)
	}

	@Test
	fun `a directory entry listed after its children upgrades the synthesized one`() {
		val jar =
			zip {
				add("d/F.txt", "x")
				add("d/")
			}
		val listing = JarListing.read(jar.toPath())

		assertThat(listing.size()).isEqualTo(3)
		assertThat(listing.localHeaderOffset(listing.find("/d"))).isAtLeast(0L)
		assertThat(children(listing, "/d")).containsExactly("F.txt")
	}

	@Test
	fun `name accessors agree`() {
		val listing = JarListing.read(fixture().toPath())

		val i = listing.find("/a/b/C.class")
		assertThat(listing.name(i)).isEqualTo("/a/b/C.class")
		assertThat(listing.shortName(i)).isEqualTo("C.class")
		assertThat(String(listing.nameBytes(i))).isEqualTo("/a/b/C.class")
		assertThat(listing.find(listing.nameBytes(i))).isEqualTo(i)
	}

	@Test
	fun `non-ascii names round trip`() {
		val jar = zip { add("päckage/Kläss.class", "x") }
		val listing = JarListing.read(jar.toPath())

		val i = listing.find("/päckage/Kläss.class")
		assertThat(i).isAtLeast(0)
		assertThat(listing.shortName(i)).isEqualTo("Kläss.class")
	}

	@Test
	fun `an empty jar has only the root`() {
		val jar = zip { }
		val listing = JarListing.read(jar.toPath())

		assertThat(listing.size()).isEqualTo(1)
		assertThat(listing.firstChild(0)).isEqualTo(-1)
	}

	@Test
	fun `retained bytes stay under 100 per entry plus names`() {
		val jar =
			zip {
				for (pkg in 0 until 100) {
					for (cls in 0 until 100) {
						add("com/example/p$pkg/Class$cls.class", "c")
					}
				}
			}
		val listing = JarListing.read(jar.toPath())

		var nameBytes = 0L
		for (i in 0 until listing.size()) {
			nameBytes += listing.nameBytes(i).size
		}
		assertThat(listing.size()).isAtLeast(10_000)
		assertThat(listing.retainedBytes()).isLessThan(10_000L * 40 + nameBytes)
	}

	@Test
	fun `a truncated jar fails with ZipException`() {
		val jar = fixture()
		val bytes = jar.readBytes()
		val truncated = File(tmpDir, "truncated.jar")
		truncated.writeBytes(bytes.copyOf(bytes.size - 30))

		try {
			JarListing.read(truncated.toPath())
			throw AssertionError("expected ZipException")
		} catch (expected: ZipException) {
		}
	}

	@Test
	fun `a file that is not a zip fails with ZipException`() {
		val junk = File(tmpDir, "junk.jar")
		junk.writeBytes(ByteArray(500) { 7 })

		try {
			JarListing.read(junk.toPath())
			throw AssertionError("expected ZipException")
		} catch (expected: ZipException) {
		}
	}

	@Test
	fun `a corrupt central directory signature fails with ZipException`() {
		val jar = fixture()
		val bytes = jar.readBytes()
		val cen = indexOf(bytes, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
		bytes[cen + 2] = 0x7f
		val corrupt = File(tmpDir, "corrupt.jar").apply { writeBytes(bytes) }

		try {
			JarListing.read(corrupt.toPath())
			throw AssertionError("expected ZipException")
		} catch (expected: ZipException) {
		}
	}

	@Test
	fun `zip64 sizes and offsets are read from the extra field`() {
		val jar = fixture()
		val bytes = jar.readBytes()
		val patched = File(tmpDir, "zip64.jar").apply { writeBytes(withZip64Entry(bytes)) }

		val listing = JarListing.read(patched.toPath())
		val i = listing.find("/a/D.class")
		assertThat(listing.size(i)).isEqualTo(ZIP64_SIZE)
		assertThat(listing.compressedSize(i)).isEqualTo(ZIP64_COMPRESSED_SIZE)
		assertThat(listing.localHeaderOffset(i)).isEqualTo(ZIP64_OFFSET)
		assertThat(listing.find("/a/b/C.class")).isAtLeast(0)
	}

	private fun normalize(name: String): String {
		val collapsed = ("/" + name).replace(Regex("/+"), "/")
		return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
	}

	private fun indexOf(
		haystack: ByteArray,
		needle: ByteArray,
	): Int {
		for (i in 0..haystack.size - needle.size) {
			if (needle.indices.all { haystack[i + it] == needle[it] }) return i
		}
		return -1
	}

	/*
	 * Rewrites the central directory header of "a/D.class" to carry zip64 values in an extra
	 * field. The local header is untouched, since only the central directory is read.
	 */
	private fun withZip64Entry(original: ByteArray): ByteArray {
		val name = "a/D.class".toByteArray()
		var pos = indexOf(original, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
		while (true) {
			val nlen = u16(original, pos + 28)
			val elen = u16(original, pos + 30)
			val clen = u16(original, pos + 32)
			val next = pos + 46 + nlen + elen + clen
			if (original.copyOfRange(pos + 46, pos + 46 + nlen).contentEquals(name)) {
				val extra = ByteArray(4 + 24)
				put16(extra, 0, 1)
				put16(extra, 2, 24)
				put64(extra, 4, ZIP64_SIZE)
				put64(extra, 12, ZIP64_COMPRESSED_SIZE)
				put64(extra, 20, ZIP64_OFFSET)
				val headerEnd = pos + 46 + nlen
				val out = original.copyOfRange(0, headerEnd) + extra + original.copyOfRange(headerEnd, original.size)
				put32(out, pos + 20, 0xFFFFFFFFL)
				put32(out, pos + 24, 0xFFFFFFFFL)
				put32(out, pos + 42, 0xFFFFFFFFL)
				put16(out, pos + 30, elen + extra.size)
				val end = indexOf(out, byteArrayOf(0x50, 0x4b, 0x05, 0x06))
				put32(out, end + 12, u32(out, end + 12) + extra.size)
				return out
			}
			pos = next
		}
	}

	private fun u16(
		b: ByteArray,
		o: Int,
	) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

	private fun u32(
		b: ByteArray,
		o: Int,
	) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

	private fun put16(
		b: ByteArray,
		o: Int,
		v: Int,
	) {
		b[o] = v.toByte()
		b[o + 1] = (v shr 8).toByte()
	}

	private fun put32(
		b: ByteArray,
		o: Int,
		v: Long,
	) {
		for (k in 0 until 4) b[o + k] = (v shr (8 * k)).toByte()
	}

	private fun put64(
		b: ByteArray,
		o: Int,
		v: Long,
	) {
		for (k in 0 until 8) b[o + k] = (v shr (8 * k)).toByte()
	}

	private companion object {
		const val ZIP64_SIZE = 5_000_000_000L
		const val ZIP64_COMPRESSED_SIZE = 4_500_000_000L
		const val ZIP64_OFFSET = 6_000_000_000L
	}
}
