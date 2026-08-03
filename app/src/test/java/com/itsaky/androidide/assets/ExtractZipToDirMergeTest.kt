package com.itsaky.androidide.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractZipToDirMergeTest {
	// Real archives merged in production (e.g. plugin-maven-repo.zip, built by Gradle's
	// Zip task -- verified via `unzip -l assets/plugin-maven-repo.zip`) always carry an
	// explicit directory entry for every ancestor path. extractZipToDir relies on that
	// (it only Files.createDirectories() on directory entries, not on every file
	// entry's parent), so mirror that shape here rather than writing bare file entries.
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

	@Test
	fun `overlay merges without wiping existing files`() {
		val dest =
			Files.createTempDirectory("mvn").also {
				Files.createDirectories(it.resolve("com/foo/1.0"))
				Files.write(it.resolve("com/foo/1.0/foo-1.0.jar"), "harvested".toByteArray())
			}

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
	}

	@Test
	fun `rejects path traversal`() {
		val dest = Files.createTempDirectory("mvn")
		assertThrows(IllegalStateException::class.java) {
			AssetsInstallationHelper.extractZipToDir(zipOf("../evil.jar" to "x"), dest)
		}
	}

	@Test
	fun `rejects extraction over an existing symlink`() {
		val dest = Files.createTempDirectory("mvn")
		val outsideTarget = Files.createTempDirectory("outside").resolve("payload")

		Files.createSymbolicLink(dest.resolve("evil.jar"), outsideTarget)

		assertThrows(IllegalStateException::class.java) {
			AssetsInstallationHelper.extractZipToDir(zipOf("evil.jar" to "x"), dest)
		}
	}

	@Test
	fun `rejects extraction into a symlinked parent that escapes destDir`() {
		val dest = Files.createTempDirectory("mvn")
		val outside = Files.createTempDirectory("outside")

		Files.createSymbolicLink(dest.resolve("linked"), outside)

		assertThrows(IllegalStateException::class.java) {
			AssetsInstallationHelper.extractZipToDir(zipOf("linked/nested.txt" to "x"), dest)
		}
	}
}
