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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.nio.file.Files
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
}
