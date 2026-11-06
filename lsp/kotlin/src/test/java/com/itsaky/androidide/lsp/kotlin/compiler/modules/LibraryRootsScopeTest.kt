package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.vfs.ListingJarFileSystem
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.zipfs2.listing.JarListing
import com.itsaky.androidide.zipfs2.listing.JarListingCache
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class LibraryRootsScopeTest : KtLspTest() {
	private val fileSystem = ListingJarFileSystem(JarListingCache(JarListing::read))

	@Test
	fun `scope still contains an entry of a jar replaced in place`() {
		val jar = writeJar("a.jar", "p/A.class")
		val oldRoot = fileSystem.findFileByPath("$jar!/")!!
		val scope = LibraryRootsScope(env.project, setOf(oldRoot))

		writeJar("a.jar", "p/A.class", "p/B.class")
		Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 10_000))
		val replaced = fileSystem.findFileByPath("$jar!/p/B.class")!!

		assertThat(oldRoot.isValid).isFalse()
		assertThat(scope.contains(replaced)).isTrue()
	}

	@Test
	fun `scope does not contain an entry of another jar`() {
		val scope = LibraryRootsScope(env.project, setOf(fileSystem.findFileByPath("${writeJar("a.jar", "p/A.class")}!/")!!))

		val foreign = fileSystem.findFileByPath("${writeJar("b.jar", "p/A.class")}!/p/A.class")!!

		assertThat(scope.contains(foreign)).isFalse()
	}

	private fun writeJar(
		name: String,
		vararg entries: String,
	): Path {
		val jar =
			lspTestRule.tempDir.root
				.toPath()
				.resolve(name)
		ZipOutputStream(jar.outputStream()).use { zip ->
			entries.forEach {
				zip.putNextEntry(ZipEntry(it))
				zip.write(1)
				zip.closeEntry()
			}
		}
		return jar
	}
}
