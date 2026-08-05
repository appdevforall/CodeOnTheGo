package com.itsaky.androidide.assets

import android.content.Context
import com.itsaky.androidide.assets.AssetsInstallationHelper.Result.Failure
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AssetsInstallationHelperTest {
	private val ctx: Context = mockk(relaxed = true)

	@Before
	fun setup() {
		mockkObject(AssetsInstallationHelper)
	}

	@Test
	fun `install with missing asset skips glitchtip`() =
		runBlocking {
			val helper = AssetsInstallationHelper

			every {
				helper["checkStorageAccessibility"](any<Context>(), any<AssetsInstallerProgressConsumer>())
			} returns null

			coEvery {
				helper["doInstall"](any<Context>(), any<AssetsInstallerProgressConsumer>())
			} throws FileNotFoundException("data/common/gradle.zip.br")

			val result = helper.install(ctx)

			assertTrue("Expected Result.Failure", result is Failure)
			val failure = result as Failure
			assertFalse("Should skip GlitchTip report", failure.shouldReportToGlitchTip)
			assertTrue(
				"Expected MissingAssetsEntryException as cause",
				failure.cause is MissingAssetsEntryException,
			)
			assertTrue(
				"Expected FileNotFoundException as root cause",
				(failure.cause?.cause) is FileNotFoundException,
			)
		}

	@Test
	fun `extractZipToDir creates parent directories for nested entries with no directory entries`() {
		val destDir = Files.createTempDirectory("extract-zip-to-dir-test")
		try {
			val content = "test notice content"
			val zipBytes =
				ByteArrayOutputStream().use { baos ->
					ZipOutputStream(baos).use { zos ->
						// No directory entries, matching how android-sdk.zip is packaged.
						zos.putNextEntry(ZipEntry("build-tools/35.0.0/NOTICE.txt"))
						zos.write(content.toByteArray())
						zos.closeEntry()
					}
					baos.toByteArray()
				}

			AssetsInstallationHelper.extractZipToDir(ByteArrayInputStream(zipBytes), destDir)

			val extracted = destDir.resolve("build-tools/35.0.0/NOTICE.txt")
			assertTrue("Expected extracted file to exist", Files.exists(extracted))
			assertEquals(content, String(Files.readAllBytes(extracted)))
		} finally {
			destDir.toFile().deleteRecursively()
		}
	}

	@Test
	fun `extractZipToDir rejects a file entry whose pre-existing symlinked grandparent escapes destDir`() {
		val destDir = Files.createTempDirectory("extract-zip-to-dir-test")
		val outsideDir = Files.createTempDirectory("extract-zip-to-dir-outside")
		try {
			Files.createSymbolicLink(destDir.resolve("linked"), outsideDir)

			val content = "escaping content"
			val zipBytes =
				ByteArrayOutputStream().use { baos ->
					ZipOutputStream(baos).use { zos ->
						// Two levels below the symlink ("linked/sub/nested.txt", no directory
						// entries), not one: for a one-level entry ("linked/nested.txt"),
						// destFile.parent IS the symlink, so Files.createDirectories() throws
						// FileAlreadyExistsException (NOFOLLOW_LINKS rejects the existing
						// symlink-to-dir) before the toRealPath() guard below it ever runs. One
						// level deeper, createDirectories() silently traverses the symlink to
						// create "sub" for real inside outsideDir, and only then does the
						// toRealPath() check on destFile.parent fire -- which is what this test
						// exercises.
						zos.putNextEntry(ZipEntry("linked/sub/nested.txt"))
						zos.write(content.toByteArray())
						zos.closeEntry()
					}
					baos.toByteArray()
				}

			assertThrows(IllegalStateException::class.java) {
				AssetsInstallationHelper.extractZipToDir(ByteArrayInputStream(zipBytes), destDir)
			}
		} finally {
			outsideDir.deleteRecursivelyWithoutFollowingLinks()
			destDir.deleteRecursivelyWithoutFollowingLinks()
		}
	}

	// Deletes a directory tree without following symlinks it contains, unlike
	// File.deleteRecursively(). Files.walkFileTree() doesn't follow symlinks unless
	// FileVisitOption.FOLLOW_LINKS is passed (it isn't here), so a symlink is visited
	// as a leaf via visitFile() -- deleting it unlinks the link itself, never the
	// target it points to. Needed because the symlink test above symlinks out of destDir.
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
}
