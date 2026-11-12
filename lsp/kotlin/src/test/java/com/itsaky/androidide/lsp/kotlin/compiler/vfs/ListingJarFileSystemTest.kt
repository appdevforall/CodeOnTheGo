package com.itsaky.androidide.lsp.kotlin.compiler.vfs

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.zipfs2.listing.JarListing
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Extends KtLspTest because CoreJarFileSystem reads contents through an application service.
class ListingJarFileSystemTest : KtLspTest() {
	private val cache = JarListingCache(JarListing::read)
	private val fileSystem = ListingJarFileSystem(cache)

	@Test
	fun `the same entry is the same object while referenced`() {
		val jar = writeJar("a.jar", "p/A.class" to "a", "p/q/B.class" to "b")

		val first = find(jar, "p/q/B.class")
		val second = find(jar, "p/q/B.class")
		val viaParent = find(jar, "p/q")!!.findChild("B.class")
		val viaChildren = find(jar, "p/q")!!.children.single()

		assertThat(second).isSameInstanceAs(first)
		assertThat(viaParent).isSameInstanceAs(first)
		assertThat(viaChildren).isSameInstanceAs(first)
	}

	@Test
	fun `finding one entry creates only it and its parents`() {
		val jar = writeJar("a.jar", "p/A.class" to "a", "p/q/B.class" to "b", "p/q/C.class" to "c", "r/D.class" to "d")

		val file = find(jar, "p/q/B.class") as ListingJarVirtualFile

		assertThat(file.handler.createdFileCount()).isEqualTo(4)
	}

	@Test
	fun `children, sizes and contents match ZipFile`() {
		val jar =
			writeJar(
				"a.jar",
				"META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
				"p/A.class" to "a".repeat(100),
				"p/q/B.class" to "bb",
				"p/q/C.class" to "",
			)

		ZipFile(jar.toFile()).use { zip ->
			for (entry in zip.entries()) {
				val file = find(jar, entry.name)!!
				assertThat(file.isDirectory).isFalse()
				assertThat(file.length).isEqualTo(entry.size)
				assertThat(file.contentsToByteArray()).isEqualTo(zip.getInputStream(entry).readBytes())
			}
		}
		assertThat(childNames(find(jar, "")!!)).containsExactly("META-INF", "p")
		assertThat(childNames(find(jar, "p")!!)).containsExactly("A.class", "q")
		assertThat(childNames(find(jar, "p/q")!!)).containsExactly("B.class", "C.class")
		assertThat(find(jar, "p/q/B.class")!!.children).isEmpty()
	}

	@Test
	fun `urls and paths match CoreJarFileSystem's`() {
		val implicitDirs = writeJar("implicit.jar", "p/A.class" to "a", "p/q/B.class" to "b")
		val explicitDirs = writeJar("explicit.jar", "p/" to null, "p/A.class" to "a", "p/q/" to null, "p/q/B.class" to "b")
		val core = CoreJarFileSystem()

		for (jar in listOf(implicitDirs, explicitDirs)) {
			for (entry in listOf("", "p", "p/", "p/q", "p/A.class", "p/q/B.class", "p//q/B.class", "missing")) {
				assertSameShape(find(jar, entry), core.findFileByPath("$jar!/$entry"), "$jar!/$entry")
			}
		}
	}

	@Test
	fun `a jar replaced in place invalidates old files and serves new content`() {
		val jar = writeJar("a.jar", "p/A.class" to "old")
		val old = find(jar, "p/A.class")!!
		val oldRoot = find(jar, "")!!

		writeJar("a.jar", "p/A.class" to "new content", "p/B.class" to "b")
		Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 10_000))
		val replaced = find(jar, "p/A.class")!!

		assertThat(old.isValid).isFalse()
		assertThat(oldRoot.isValid).isFalse()
		assertThat(oldRoot.findChild("p")!!.findChild("A.class")).isSameInstanceAs(old)
		assertThat(replaced).isNotSameInstanceAs(old)
		assertThat(replaced.isValid).isTrue()
		assertThat(replaced.contentsToByteArray().decodeToString()).isEqualTo("new content")
		assertThat(find(jar, "p/B.class")).isNotNull()
	}

	@Test
	fun `clearing the cache invalidates every file and serves fresh ones`() {
		val one = find(writeJar("one.jar", "p/A.class" to "a"), "p/A.class")!!
		val two = writeJar("two.jar", "q/B.class" to "b")
		val held = find(two, "q/B.class")!!

		cache.clear()

		assertThat(one.isValid).isFalse()
		assertThat(held.isValid).isFalse()
		assertThat(find(two, "q/B.class")!!.isValid).isTrue()
	}

	@Test
	fun `stored and deflated contents behind a stub prefix match ZipFile`() {
		val jar =
			lspTestRule.tempDir.root
				.toPath()
				.resolve("prefixed.jar")
		val stored = setOf("p/Stored.class", "p/Empty.class")
		val zip =
			zipBytes(
				"p/Stored.class" to "stored ".repeat(50),
				"p/Deflated.class" to "deflated ".repeat(500),
				"p/Empty.class" to "",
				stored = stored,
			)
		Files.write(jar, "#!/bin/sh stub\n".encodeToByteArray() + zip)

		ZipFile(jar.toFile()).use { zipFile ->
			assertThat(
				zipFile
					.entries()
					.toList()
					.map { it.method }
					.toSet(),
			).containsExactly(ZipEntry.STORED, ZipEntry.DEFLATED)
			for (entry in zipFile.entries()) {
				assertThat(find(jar, entry.name)!!.contentsToByteArray()).isEqualTo(zipFile.getInputStream(entry).readBytes())
			}
		}
	}

	@Test
	fun `a held file keeps reading the old jar after it is replaced by a rename`() {
		val jar = writeJar("a.jar", "p/A.class" to "old")
		val held = find(jar, "p/A.class")!!
		held.contentsToByteArray()

		renameOver(jar, "p/A.class" to "new and longer", "p/B.class" to "b")

		assertThat(held.contentsToByteArray().decodeToString()).isEqualTo("old")
	}

	@Test
	fun `corrupt deflate data fails the read with an IOException`() {
		val jar =
			lspTestRule.tempDir.root
				.toPath()
				.resolve("corrupt.jar")
		val bytes = zipBytes("p/A.class" to "some deflated content")
		// 0xFF opens a final block of reserved type 3, which no deflate stream may contain.
		bytes[LOC_HEADER_SIZE + "p/A.class".length] = 0xFF.toByte()
		Files.write(jar, bytes)

		val failure = runCatching { find(jar, "p/A.class")!!.contentsToByteArray() }.exceptionOrNull()

		assertThat(failure).isInstanceOf(IOException::class.java)
	}

	@Test
	fun `a first read after a rename replaced the jar fails instead of using old offsets`() {
		val jar = writeJar("a.jar", "p/A.class" to "old")
		val held = find(jar, "p/A.class")!!

		renameOver(jar, "p/A.class" to "new and longer", "p/B.class" to "b")

		assertThat(runCatching { held.contentsToByteArray() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
	}

	@Test
	fun `a lookup holding an older listing keeps the newer handler`() {
		val jar = writeJar("a.jar", "p/A.class" to "old")
		val stale = cache.snapshot(jar)
		writeJar("a.jar", "p/A.class" to "new content")
		Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 10_000))
		val current = find(jar, "p/A.class") as ListingJarVirtualFile

		val kept = fileSystem.handlerFor(jar.toAbsolutePath().normalize().toString(), stale)

		assertThat(kept).isSameInstanceAs(current.handler)
		assertThat(current.isValid).isTrue()
		assertThat(find(jar, "p/A.class")).isSameInstanceAs(current)
	}

	@Test
	fun `a first read after a same-size rename with other names fails`() {
		val jar = writeJar("a.jar", "p/A.class" to "same")
		val held = find(jar, "p/A.class")!!
		val size = Files.size(jar)

		renameOver(jar, "p/B.class" to "same")

		assertThat(Files.size(jar)).isEqualTo(size)
		assertThat(runCatching { held.contentsToByteArray() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
	}

	@Test
	fun `a lookup with an old snapshot after another consumer rebuilt the listing leaves no stale handler`() {
		val jar = writeJar("a.jar", "p/A.class" to "old")
		val key = jar.toAbsolutePath().normalize().toString()
		val stale = cache.snapshot(jar)
		find(jar, "p/A.class")
		writeJar("a.jar", "p/A.class" to "new content")
		Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 10_000))
		val rebuilt = cache.snapshot(jar)

		val handler = fileSystem.handlerFor(key, stale)!!

		assertThat(handler.snapshot).isSameInstanceAs(rebuilt)
		assertThat(fileSystem.handlerFor(key, rebuilt)).isSameInstanceAs(handler)
		assertThat(handler.findFile("p/A.class")!!.contentsToByteArray().decodeToString()).isEqualTo("new content")
	}

	@Test
	fun `a deleted jar invalidates its files`() {
		val jar = writeJar("a.jar", "p/A.class" to "a")
		val file = find(jar, "p/A.class")!!

		Files.delete(jar)

		assertThat(find(jar, "p/A.class")).isNull()
		assertThat(file.isValid).isFalse()
	}

	@Test
	fun `a missing jar finds nothing`() {
		assertThat(fileSystem.findFileByPath("${lspTestRule.tempDir.root}/missing.jar!/p/A.class")).isNull()
	}

	private fun find(
		jar: Path,
		entry: String,
	): VirtualFile? = fileSystem.findFileByPath("$jar!/$entry")

	private fun childNames(dir: VirtualFile): List<String> = dir.children.map { it.name }

	private fun assertSameShape(
		actual: VirtualFile?,
		expected: VirtualFile?,
		what: String,
	) {
		if (expected == null) {
			assertThat(actual).isNull()
			return
		}
		checkNotNull(actual) { "missing $what" }
		assertThat(actual.url).isEqualTo(expected.url)
		assertThat(actual.path).isEqualTo(expected.path)
		assertThat(actual.name).isEqualTo(expected.name)
		assertThat(actual.isDirectory).isEqualTo(expected.isDirectory)
		assertThat(actual.length).isEqualTo(expected.length)
		assertThat(actual.timeStamp).isEqualTo(expected.timeStamp)
		assertThat(actual.modificationStamp).isEqualTo(expected.modificationStamp)
		assertThat(actual.isWritable).isEqualTo(expected.isWritable)
		assertThat(actual.fileSystem.protocol).isEqualTo(expected.fileSystem.protocol)
		assertThat(actual.fileSystem).isSameInstanceAs(fileSystem)
		assertThat(actual.parent?.url).isEqualTo(expected.parent?.url)
		assertThat(childNames(actual)).containsExactlyElementsIn(childNames(expected))
		if (!expected.isDirectory) {
			assertThat(actual.contentsToByteArray()).isEqualTo(expected.contentsToByteArray())
		}
	}

	private fun writeJar(
		name: String,
		vararg entries: Pair<String, String?>,
	): Path {
		val jar =
			lspTestRule.tempDir.root
				.toPath()
				.resolve(name)
		Files.write(jar, zipBytes(*entries))
		return jar
	}

	private fun renameOver(
		jar: Path,
		vararg entries: Pair<String, String?>,
	) {
		val replacement = Files.write(jar.resolveSibling("replacement.tmp"), zipBytes(*entries))
		Files.move(replacement, jar, StandardCopyOption.ATOMIC_MOVE)
	}

	// A null content writes a directory entry.
	private fun zipBytes(
		vararg entries: Pair<String, String?>,
		stored: Set<String> = emptySet(),
	): ByteArray {
		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { zip ->
			entries.forEach { (name, content) ->
				val data = content?.encodeToByteArray() ?: ByteArray(0)
				val entry = ZipEntry(name)
				if (name in stored) {
					entry.method = ZipEntry.STORED
					entry.size = data.size.toLong()
					entry.crc = CRC32().apply { update(data) }.value
				}
				zip.putNextEntry(entry)
				zip.write(data)
				zip.closeEntry()
			}
		}
		return bytes.toByteArray()
	}
}

private const val LOC_HEADER_SIZE = 30
