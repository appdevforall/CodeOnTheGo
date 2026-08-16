package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for ZipUtils.unzipFile, including its zip-slip (path traversal) protection
 * introduced when replacing com.blankj:utilcodex's ZipUtils with an in-house implementation
 * (ADFA-4649).
 */
class ZipUtilsTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun `unzipFile extracts nested directories and files`() {
		val zipFile = tempFolder.newFile("archive.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("dir/"))
			zip.closeEntry()

			zip.putNextEntry(ZipEntry("dir/nested.txt"))
			zip.write("nested content".toByteArray())
			zip.closeEntry()

			zip.putNextEntry(ZipEntry("root.txt"))
			zip.write("root content".toByteArray())
			zip.closeEntry()
		}

		val destDir = tempFolder.newFolder("dest")
		ZipUtils.unzipFile(zipFile, destDir)

		assertThat(File(destDir, "dir/nested.txt").readText()).isEqualTo("nested content")
		assertThat(File(destDir, "root.txt").readText()).isEqualTo("root content")
	}

	@Test
	fun `unzipFile rejects entries that traverse outside the destination directory`() {
		val zipFile = tempFolder.newFile("evil.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("../evil.txt"))
			zip.write("evil content".toByteArray())
			zip.closeEntry()
		}

		val destDir = tempFolder.newFolder("dest")

		assertThrows(IOException::class.java) { ZipUtils.unzipFile(zipFile, destDir) }

		val escapedFile = File(destDir.parentFile, "evil.txt")
		assertThat(escapedFile.exists()).isFalse()
	}

	@Test
	fun `unzipFile refuses to extract over an existing symlink`() {
		val destDir = tempFolder.newFolder("dest")
		val realFile = File(destDir, "real.txt").apply { writeText("original") }
		val linkPath = File(destDir, "link.txt").toPath()
		val symlinkCreated =
			try {
				Files.createSymbolicLink(linkPath, realFile.toPath())
				true
			} catch (e: UnsupportedOperationException) {
				// The filesystem itself doesn't support symlinks (e.g. FAT32).
				false
			} catch (e: FileSystemException) {
				// Windows NTFS supports symlinks but requires an elevated/Developer Mode privilege to
				// create them -- without it, creation fails with this (a permission error), not
				// UnsupportedOperationException.
				false
			}
		// Report as skipped, not silently passed, when this environment can't create symlinks.
		Assume.assumeTrue("Symlinks are not supported/permitted on this filesystem", symlinkCreated)

		// The symlink's target is inside destDir, so the canonical-path containment check alone
		// would pass -- this isolates the separate, explicit isSymbolicLink guard.
		val zipFile = tempFolder.newFile("archive.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("link.txt"))
			zip.write("payload".toByteArray())
			zip.closeEntry()
		}

		assertThrows(IOException::class.java) { ZipUtils.unzipFile(zipFile, destDir) }

		assertThat(Files.isSymbolicLink(linkPath)).isTrue()
		assertThat(realFile.readText()).isEqualTo("original")
	}
}
