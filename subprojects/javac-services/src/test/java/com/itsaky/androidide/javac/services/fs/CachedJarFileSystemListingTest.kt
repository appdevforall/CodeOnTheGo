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
import com.itsaky.androidide.zipfs2.ZipFileSystemProbe
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * A [CachedJarFileSystem] reads its JAR through the shared listing and must behave like the JDK's
 * own zip file system on the same JAR.
 */
class CachedJarFileSystemListingTest {
	private lateinit var tmpDir: File
	private lateinit var jarPath: Path

	@Before
	fun setUp() {
		CachingJarFileSystemProvider.clearCache()
		tmpDir = Files.createTempDirectory("jar-listing-fs").toFile()
		jarPath = File(tmpDir, "fixture.jar").toPath()
		jarPath.writeBytes(fixtureJar())
	}

	@After
	fun tearDown() {
		CachingJarFileSystemProvider.clearCache()
		tmpDir.deleteRecursively()
	}

	@Test
	fun `walk yields the same paths and directory flags as jdk zipfs`() {
		val expected = jdkZipFs(jarPath).use { walk(it) }

		assertThat(walk(openFs(jarPath))).isEqualTo(expected)
	}

	@Test
	fun `every file reads the same bytes as ZipFile`() {
		assertReadsLikeZipFile(jarPath)
	}

	@Test
	fun `a jar with bytes before the archive reads the same bytes as ZipFile`() {
		val prefixed = File(tmpDir, "prefixed.jar").toPath()
		prefixed.writeBytes(ByteArray(37) { 'x'.code.toByte() } + fixtureJar())

		assertReadsLikeZipFile(prefixed)
	}

	@Test
	fun `synthesized directories exist and list their children`() {
		val fs = openFs(jarPath)
		val example = fs.getPath("/com/example")

		assertThat(Files.isDirectory(example)).isTrue()
		assertThat(Files.isDirectory(fs.getPath("/META-INF/versions/9/x"))).isTrue()
		assertThat(childNames(example)).containsExactly("A.class", "sub")
	}

	@Test
	fun `listing mode keeps no index nodes`() {
		val fs = openFs(jarPath)

		assertThat(ZipFileSystemProbe.retainedIndexNodeCount(fs)).isEqualTo(0)
	}

	@Test
	fun `a jar replaced in place is read fresh, never with old offsets`() {
		val v1 = jarOf(mapOf("a/One.txt" to "v1".toByteArray()))
		val v2Content = "the second version is longer".toByteArray()
		val v2 = jarOf(mapOf("b/Two.txt" to ByteArray(300) { 7 }, "a/One.txt" to v2Content))
		jarPath.writeBytes(v1)
		val first = openFs(jarPath)
		assertThat(first.getPath("/a/One.txt").readBytes()).isEqualTo("v1".toByteArray())

		jarPath.writeBytes(v2)
		val second = openFs(jarPath)

		assertThat(second.getPath("/a/One.txt").readBytes()).isEqualTo(v2Content)
		assertThat(second).isNotSameInstanceAs(first)
	}

	@Test
	fun `a superseded file system stays open until retired file systems are closed`() {
		val first = openFs(jarPath)
		rewrite(jarPath, jarOf(mapOf("a/One.txt" to "v2".toByteArray())))
		openFs(jarPath)

		assertThat(Files.exists(first.getPath("/com/example/A.class"))).isTrue()

		CachingJarFileSystemProvider.closeRetired()

		assertThat(first.isOpen).isFalse()
	}

	@Test
	fun `a dropped listing leaves the cached file system open for its holders`() {
		val first = openFs(jarPath)

		JarListingCache.SHARED.invalidate(jarPath)

		assertThat(first.isOpen).isTrue()
		assertThat(openFs(jarPath)).isNotSameInstanceAs(first)
	}

	@Test
	fun `clearCache closes retired file systems too`() {
		val first = openFs(jarPath)
		rewrite(jarPath, jarOf(mapOf("a/One.txt" to "v2".toByteArray())))
		val second = openFs(jarPath)

		CachingJarFileSystemProvider.clearCache()

		assertThat(first.isOpen).isFalse()
		assertThat(second.isOpen).isFalse()
	}

	private fun rewrite(
		path: Path,
		bytes: ByteArray,
	) {
		val mtime = Files.getLastModifiedTime(path).toMillis()
		path.writeBytes(bytes)
		Files.setLastModifiedTime(path, FileTime.fromMillis(mtime + 5_000))
	}

	@Test
	fun `a corrupt jar still fails to open with ZipException`() {
		val truncated = File(tmpDir, "truncated.jar").toPath()
		truncated.writeBytes(fixtureJar().copyOf(64))
		val empty = File(tmpDir, "empty.jar").toPath()
		empty.writeBytes(ByteArray(0))

		assertThrows(ZipException::class.java) { openFs(truncated) }
		assertThrows(ZipException::class.java) { openFs(empty) }
	}

	private fun openFs(path: Path) = CachingJarFileSystemProvider.newFileSystem(path) as CachedJarFileSystem

	private fun jdkZipFs(path: Path): FileSystem = FileSystems.newFileSystem(path, null as ClassLoader?)

	private fun walk(fs: FileSystem): Set<Pair<String, Boolean>> =
		Files.walk(fs.getPath("/")).use { paths ->
			paths
				.iterator()
				.asSequence()
				.map { it.toString().removeSuffix("/") to Files.isDirectory(it) }
				.toSet()
		}

	private fun childNames(dir: Path): List<String> =
		Files.newDirectoryStream(dir).use { stream -> stream.map { it.fileName.toString().removeSuffix("/") } }

	private fun assertReadsLikeZipFile(path: Path) {
		val fs = openFs(path)
		ZipFile(path.toFile()).use { zip ->
			val files = zip.entries().toList().filterNot { it.isDirectory }
			assertThat(files).isNotEmpty()
			for (entry in files) {
				val expected = zip.getInputStream(entry).use { it.readBytes() }
				assertThat(fs.getPath("/" + entry.name).readBytes()).isEqualTo(expected)
			}
		}
	}
}

private fun fixtureJar(): ByteArray {
	val big = ByteArray(200_000).also { Random(42).nextBytes(it) }
	for (i in big.indices step 3) {
		big[i] = 0
	}
	val out = ByteArrayOutputStream()
	ZipOutputStream(out).use { zip ->
		zip.putNextEntry(ZipEntry("com/"))
		zip.closeEntry()
		zip.writeDeflated("com/example/A.class", ByteArray(64) { it.toByte() })
		zip.writeStored("com/example/sub/B.txt", "stored entry".toByteArray())
		zip.writeDeflated("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".toByteArray())
		zip.writeDeflated("META-INF/versions/9/x/Y.class", ByteArray(16))
		zip.writeDeflated("empty.txt", ByteArray(0))
		zip.writeDeflated("big/Data.bin", big)
	}
	return out.toByteArray()
}

private fun jarOf(entries: Map<String, ByteArray>): ByteArray {
	val out = ByteArrayOutputStream()
	ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.writeDeflated(name, bytes) } }
	return out.toByteArray()
}

private fun ZipOutputStream.writeDeflated(
	name: String,
	bytes: ByteArray,
) {
	putNextEntry(ZipEntry(name))
	write(bytes)
	closeEntry()
}

private fun ZipOutputStream.writeStored(
	name: String,
	bytes: ByteArray,
) {
	val entry = ZipEntry(name)
	entry.method = ZipEntry.STORED
	entry.size = bytes.size.toLong()
	entry.crc = CRC32().also { it.update(bytes) }.value
	putNextEntry(entry)
	write(bytes)
	closeEntry()
}
