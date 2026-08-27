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

	// The lexical guard used to be a bare substring reject, so an entry legitimately named with
	// consecutive dots aborted the whole installation. The shared resolver rejects a ".." *segment*
	// instead, which lets a name like this through.
	@Test
	fun `extracts an entry whose name merely contains a double dot`() {
		val destDir = Files.createTempDirectory("assets-dots")
		try {
			AssetsInstallationHelper.extractZipToDir(
				zipOf("lib/notes..txt" to "kept", "lib/a..b/c.txt" to "also kept"),
				destDir,
			)

			assertEquals("kept", destDir.resolve("lib/notes..txt").toFile().readText())
			assertEquals("also kept", destDir.resolve("lib/a..b/c.txt").toFile().readText())
		} finally {
			destDir.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	@Test
	fun `rejects path traversal`() {
		val dest = Files.createTempDirectory("mvn")
		try {
			val thrown =
				assertThrows(IllegalStateException::class.java) {
					AssetsInstallationHelper.extractZipToDir(zipOf("../evil.jar" to "x"), dest)
				}
			// A real escape is reported as one -- distinct from the symlink-refusal and
			// cannot-verify messages below.
			assertTrue(
				"expected an escape message, got: ${thrown.message}",
				thrown.message!!.contains("escapes the target dir"),
			)
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	// The installer's own policy branch: any pre-existing symlink at an entry's target refuses the
	// extraction, and says so. A *dangling* link is the case the resolver refuses before the
	// explicit isSymbolicLink check is reached, so asserting the message (not just the type) pins
	// that it still surfaces as the symlink refusal, not as a zip-slip accusation.
	@Test
	fun `rejects extraction over an existing symlink`() {
		val dest = Files.createTempDirectory("mvn")
		val outside = Files.createTempDirectory("outside")
		try {
			val outsideTarget = outside.resolve("payload")
			Files.createSymbolicLink(dest.resolve("evil.jar"), outsideTarget)

			val thrown =
				assertThrows(IllegalStateException::class.java) {
					AssetsInstallationHelper.extractZipToDir(zipOf("evil.jar" to "x"), dest)
				}
			assertTrue(
				"expected the symlink refusal message, got: ${thrown.message}",
				thrown.message!!.contains("Refusing to extract over an existing symlink"),
			)
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
			outside.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	// Same refusal for a live link whose target is inside destDir -- the resolver proves it
	// contained, and the installer's explicit isSymbolicLink check refuses it anyway.
	@Test
	fun `rejects extraction over an existing symlink pointing inside destDir`() {
		val dest = Files.createTempDirectory("mvn")
		try {
			Files.write(dest.resolve("real.jar"), "kept".toByteArray())
			Files.createSymbolicLink(dest.resolve("evil.jar"), dest.resolve("real.jar"))

			val thrown =
				assertThrows(IllegalStateException::class.java) {
					AssetsInstallationHelper.extractZipToDir(zipOf("evil.jar" to "x"), dest)
				}
			assertTrue(
				"expected the symlink refusal message, got: ${thrown.message}",
				thrown.message!!.contains("Refusing to extract over an existing symlink"),
			)
			assertEquals("kept", String(Files.readAllBytes(dest.resolve("real.jar"))))
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	// And for a live link pointing outside destDir -- refused by the resolver's real-path check,
	// still reported as the symlink refusal it is, with nothing written through the link.
	@Test
	fun `rejects extraction over an existing symlink pointing outside destDir`() {
		val dest = Files.createTempDirectory("mvn")
		val outside = Files.createTempDirectory("outside")
		try {
			val outsideTarget = outside.resolve("payload")
			Files.write(outsideTarget, "original".toByteArray())
			Files.createSymbolicLink(dest.resolve("evil.jar"), outsideTarget)

			val thrown =
				assertThrows(IllegalStateException::class.java) {
					AssetsInstallationHelper.extractZipToDir(zipOf("evil.jar" to "x"), dest)
				}
			assertTrue(
				"expected the symlink refusal message, got: ${thrown.message}",
				thrown.message!!.contains("Refusing to extract over an existing symlink"),
			)
			assertEquals("original", String(Files.readAllBytes(outsideTarget)))
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
			outside.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	// The third message: containment that cannot be *verified* (here a symlink loop, ELOOP) is
	// neither an escape nor the symlink refusal -- it aborts naming the filesystem cause, so a
	// failing install points at the disk, not at the archive.
	@Test
	fun `reports unverifiable containment distinctly`() {
		val dest = Files.createTempDirectory("mvn")
		try {
			Files.createSymbolicLink(dest.resolve("loop-a"), dest.resolve("loop-b"))
			Files.createSymbolicLink(dest.resolve("loop-b"), dest.resolve("loop-a"))

			val thrown =
				assertThrows(IllegalStateException::class.java) {
					AssetsInstallationHelper.extractZipToDir(zipOf("loop-a/file.txt" to "x"), dest)
				}
			assertTrue(
				"expected the cannot-verify message, got: ${thrown.message}",
				thrown.message!!.contains("Cannot verify"),
			)
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	// A "." or "./" root directory entry names destDir itself. Some archivers emit one, and the
	// asset zips are refreshed from an external URL -- it must be a no-op, not an aborted install.
	@Test
	fun `tolerates a root directory entry instead of aborting`() {
		val dest = Files.createTempDirectory("mvn")
		try {
			val zipBytes =
				ByteArrayOutputStream().use { baos ->
					ZipOutputStream(baos).use { zip ->
						zip.putNextEntry(ZipEntry("./"))
						zip.closeEntry()
						zip.putNextEntry(ZipEntry("com/foo/a.txt"))
						zip.write("kept".toByteArray())
						zip.closeEntry()
					}
					baos.toByteArray()
				}

			AssetsInstallationHelper.extractZipToDir(ByteArrayInputStream(zipBytes), dest)

			assertEquals("kept", String(Files.readAllBytes(dest.resolve("com/foo/a.txt"))))
		} finally {
			dest.deleteRecursivelyWithoutFollowingLinks()
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
