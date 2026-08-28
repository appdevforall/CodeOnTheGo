package com.itsaky.androidide.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractZipToDirMergeTest {
	// extractZipToDir now calls Files.createDirectories(destFile.parent) for every file
	// entry, not just directory entries, so a bare file entry with no ancestor directory
	// entries would extract fine too. zipOf still injects a directory entry for every
	// ancestor because that's how real archives merged in production (e.g.
	// plugin-maven-repo.zip, built by Gradle's Zip task -- verified via `unzip -l
	// assets/plugin-maven-repo.zip`) are actually packaged. The no-directory-entry path
	// (e.g. how android-sdk.zip is packaged) is exercised separately by
	// AssetsInstallationHelperTest's `extractZipToDir creates parent directories for
	// nested entries with no directory entries` and `extractZipToDir rejects a file
	// entry whose pre-existing symlinked grandparent escapes destDir`.
	private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
		val bos = ByteArrayOutputStream()
		ZipOutputStream(bos).use { zip ->
			val dirsWritten = LinkedHashSet<String>()
			for ((name, _) in entries) {
				val parts = name.split("/").dropLast(1)
				var prefix = ""
				for (part in parts) {
					prefix += "$part/"
					if (dirsWritten.add(prefix)) {
						zip.putNextEntry(ZipEntry(prefix))
						zip.closeEntry()
					}
				}
			}
			for ((name, body) in entries) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(body.toByteArray())
				zip.closeEntry()
			}
		}
		return ByteArrayInputStream(bos.toByteArray())
	}

	// Deletes a directory tree without following symlinks it contains, unlike
	// File.deleteRecursively(). Files.walkFileTree() doesn't follow symlinks unless
	// FileVisitOption.FOLLOW_LINKS is passed (it isn't here), so a symlink is visited
	// as a leaf via visitFile() -- deleting it unlinks the link itself, never the
	// target it points to. Needed because several tests below symlink out of dest.
	private fun Path.deleteRecursivelyWithoutFollowingLinks() {
		Files.walkFileTree(
			this,
			object : SimpleFileVisitor<Path>() {
				override fun visitFile(
					file: Path,
					attrs: BasicFileAttributes,
				): FileVisitResult {
					Files.delete(file)
					return FileVisitResult.CONTINUE
				}

				override fun postVisitDirectory(
					dir: Path,
					exc: IOException?,
				): FileVisitResult {
					Files.delete(dir)
					return FileVisitResult.CONTINUE
				}
			},
		)
	}

	@Test
	fun `overlay merges without wiping existing files`() {
		val dest =
			Files.createTempDirectory("mvn").also {
				Files.createDirectories(it.resolve("com/foo/1.0"))
				Files.write(it.resolve("com/foo/1.0/foo-1.0.jar"), "harvested".toByteArray())
			}
		try {
			AssetsInstallationHelper.extractZipToDir(
				zipOf("com/itsaky/androidide/plugin-api/1.0.0/plugin-api-1.0.0.jar" to "fat"),
				dest,
			)

			assertTrue(
				"harvested file must survive the merge",
				Files.exists(dest.resolve("com/foo/1.0/foo-1.0.jar")),
			)
			assertEquals(
				"fat",
				String(Files.readAllBytes(dest.resolve("com/itsaky/androidide/plugin-api/1.0.0/plugin-api-1.0.0.jar"))),
			)
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	@Test
	fun `extracts multiple sibling files under the same directory`() {
		val dest = Files.createTempDirectory("mvn")
		try {
			AssetsInstallationHelper.extractZipToDir(
				zipOf(
					"com/foo/1.0/a.txt" to "a",
					"com/foo/1.0/b.txt" to "b",
				),
				dest,
			)

			assertEquals("a", String(Files.readAllBytes(dest.resolve("com/foo/1.0/a.txt"))))
			assertEquals("b", String(Files.readAllBytes(dest.resolve("com/foo/1.0/b.txt"))))
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	@Test
	fun `rejects path traversal`() {
		val dest = Files.createTempDirectory("mvn")
		try {
			assertThrows(IllegalStateException::class.java) {
				AssetsInstallationHelper.extractZipToDir(zipOf("../evil.jar" to "x"), dest)
			}
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	@Test
	fun `rejects extraction over an existing symlink`() {
		val dest = Files.createTempDirectory("mvn")
		val outside = Files.createTempDirectory("outside")
		try {
			val outsideTarget = outside.resolve("payload")
			Files.createSymbolicLink(dest.resolve("evil.jar"), outsideTarget)

			assertThrows(IllegalStateException::class.java) {
				AssetsInstallationHelper.extractZipToDir(zipOf("evil.jar" to "x"), dest)
			}
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
			outside.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	@Test
	fun `rejects extraction into a symlinked parent that escapes destDir`() {
		val dest = Files.createTempDirectory("mvn")
		val outside = Files.createTempDirectory("outside")
		try {
			Files.createSymbolicLink(dest.resolve("linked"), outside)

			assertThrows(IllegalStateException::class.java) {
				AssetsInstallationHelper.extractZipToDir(zipOf("linked/nested.txt" to "x"), dest)
			}
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
			outside.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	@Test
	fun `rejects a bare directory entry that resolves to an existing symlink escaping destDir`() {
		val dest = Files.createTempDirectory("mvn")
		val outside = Files.createTempDirectory("outside")
		try {
			Files.createSymbolicLink(dest.resolve("linked"), outside)

			val zipBytes =
				ByteArrayOutputStream().use { baos ->
					ZipOutputStream(baos).use { zip ->
						zip.putNextEntry(ZipEntry("linked/"))
						zip.closeEntry()
					}
					baos.toByteArray()
				}

			assertThrows(IllegalStateException::class.java) {
				AssetsInstallationHelper.extractZipToDir(ByteArrayInputStream(zipBytes), dest)
			}
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
			outside.deleteRecursivelyWithoutFollowingLinks()
		}
	}
}
