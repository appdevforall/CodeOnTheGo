package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class IdeFileServiceImplTest {
	private lateinit var projectRoot: File

	@Before
	fun setUp() {
		projectRoot =
			File.createTempFile("ide-file-service-", "").apply {
				delete()
				mkdirs()
			}
	}

	@After
	fun tearDown() {
		projectRoot.deleteRecursively()
	}

	// The production allowlist only permits fixed on-device roots, so a test rooted
	// at a temp dir must supply its own validator that permits paths under it.
	private fun service(permissions: Set<PluginPermission> = setOf(PluginPermission.FILESYSTEM_WRITE)) =
		IdeFileServiceImpl(
			pluginId = "test-plugin",
			permissions = permissions,
			pathValidator =
				object : IdeFileServiceImpl.PathValidator {
					override fun isPathAllowed(path: File) = path.isWithin(projectRoot)

					override fun getAllowedPaths() = listOf(projectRoot.absolutePath)
				},
		)

	@Test
	fun writeFileCreatesFileWithContent() {
		val file = File(projectRoot, "new.txt")

		assertTrue(service().writeFile(file, "Content"))
		assertTrue(file.exists())
		assertEquals("Content", file.readText())
	}

	@Test
	fun readFileReturnsContent() {
		val file = File(projectRoot, "test.txt").apply { writeText("Hello World") }

		assertEquals("Hello World", service().readFile(file))
	}

	@Test
	fun readFileReturnsNullForMissingFile() {
		assertNull(service().readFile(File(projectRoot, "does-not-exist.txt")))
	}

	@Test
	fun replaceInFileUpdatesContent() {
		val file = File(projectRoot, "test.txt").apply { writeText("Original") }

		assertTrue(service().replaceInFile(file, "Original", "Updated"))
		assertEquals("Updated", file.readText())
	}

	@Test
	fun deleteRemovesFile() {
		val file = File(projectRoot, "test.txt").apply { writeText("Delete me") }

		assertTrue(service().delete(file))
		assertFalse(file.exists())
	}

	@Test
	fun listFilesNonRecursiveReturnsDirectChildrenOnly() {
		File(projectRoot, "file1.txt").writeText("1")
		File(projectRoot, "file2.txt").writeText("2")
		File(projectRoot, "subdir").mkdirs()
		File(projectRoot, "subdir/file3.txt").writeText("3")

		val names = service().listFiles(projectRoot, recursive = false).map { it.name }

		assertTrue(names.contains("file1.txt"))
		assertTrue(names.contains("file2.txt"))
		assertFalse(names.contains("file3.txt"))
	}

	@Test
	fun listFilesRecursiveReturnsNestedChildren() {
		File(projectRoot, "file1.txt").writeText("1")
		File(projectRoot, "subdir").mkdirs()
		File(projectRoot, "subdir/file2.txt").writeText("2")

		val names = service().listFiles(projectRoot, recursive = true).map { it.name }

		assertTrue(names.contains("file1.txt"))
		assertTrue(names.contains("file2.txt"))
	}

	@Test(expected = SecurityException::class)
	fun disallowedPathThrowsSecurityException() {
		service().readFile(File("/etc/passwd"))
	}

	@Test(expected = SecurityException::class)
	fun siblingPrefixPathThrowsSecurityException() {
		// A sibling dir whose path is a string prefix of the root ("<root>-sibling") must be
		// rejected -- naive absolutePath.startsWith() would wrongly accept it.
		val sibling = File(projectRoot.parentFile, "${projectRoot.name}-sibling")
		service().readFile(File(sibling, "secret.txt"))
	}

	@Test(expected = SecurityException::class)
	fun traversalPathThrowsSecurityException() {
		// A traversal that escapes the root ("<root>/../escape.txt") must be rejected -- naive
		// absolutePath.startsWith() would wrongly accept it since ".." is not normalized.
		service().readFile(File(projectRoot, "../escape.txt"))
	}

	@Test(expected = SecurityException::class)
	fun missingWritePermissionThrowsSecurityException() {
		service(permissions = emptySet()).readFile(File(projectRoot, "test.txt"))
	}

	// Canonical, component-wise containment: rejects sibling-prefix and traversal escapes that a
	// naive absolutePath.startsWith() would wrongly accept.
	private fun File.isWithin(root: File) = canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
}
