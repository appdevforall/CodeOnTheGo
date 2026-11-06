package com.itsaky.androidide.lsp.kotlin.compiler.vfs

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.zipfs2.listing.JarListing
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

// Extends KtLspTest because CoreJarFileSystem reads contents through an application service.
class ListingJarFileSystemTest : KtLspTest() {
	private val fileSystem = ListingJarFileSystem(JarListingCache(JarListing::read))

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
		assertThat(replaced).isNotSameInstanceAs(old)
		assertThat(replaced.isValid).isTrue()
		assertThat(replaced.contentsToByteArray().decodeToString()).isEqualTo("new content")
		assertThat(find(jar, "p/B.class")).isNotNull()
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

	// A null content writes a directory entry.
	private fun writeJar(
		name: String,
		vararg entries: Pair<String, String?>,
	): Path {
		val jar =
			lspTestRule.tempDir.root
				.toPath()
				.resolve(name)
		ZipOutputStream(jar.outputStream()).use { zip ->
			entries.forEach { (entry, content) ->
				zip.putNextEntry(ZipEntry(entry))
				content?.let { zip.write(it.encodeToByteArray()) }
				zip.closeEntry()
			}
		}
		return jar
	}
}
