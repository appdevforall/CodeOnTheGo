package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
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
}
