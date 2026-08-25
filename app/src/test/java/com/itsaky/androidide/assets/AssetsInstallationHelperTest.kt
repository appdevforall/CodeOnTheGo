package com.itsaky.androidide.assets

import android.content.Context
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.assets.AssetsInstallationHelper.Result.Failure
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
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
	private val helper = AssetsInstallationHelper

	@Before
	fun setup() {
		// Load the brotli native for real before anything here mocks Brotli4jLoader. brotli4j caches
		// its availability in a static field, so a JVM whose first sight of that class is a mocked
		// one keeps a "never loaded" state -- and a later *real* ensureAvailability(), which
		// BrotliDictionaryDecodeTest does in @BeforeClass, then throws UnsatisfiedLinkError even
		// after unmockkAll(). Only UnsatisfiedLinkError is absorbed -- that is what the loader raises
		// when there is no native for this host, which is a legitimate configuration (see
		// brotli4jNativeForHost) -- so any other setup failure here still surfaces.
		try {
			Brotli4jLoader.ensureAvailability()
		} catch (e: UnsatisfiedLinkError) {
			println("brotli native unavailable on this host, continuing: ${e.message}")
		}

		mockkObject(helper)
		every {
			helper["checkStorageAccessibility"](any<Context>(), any<AssetsInstallerProgressConsumer>())
		} returns null
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun assertMissingAssetFailure(result: AssetsInstallationHelper.Result): Failure {
		assertTrue("Expected Result.Failure", result is Failure)
		val failure = result as Failure
		assertTrue(
			"Expected MissingAssetsEntryException as cause",
			failure.cause is MissingAssetsEntryException,
		)
		assertTrue(
			"Expected FileNotFoundException as root cause",
			(failure.cause?.cause) is FileNotFoundException,
		)
		return failure
	}

	@Test
	fun `install with missing asset skips glitchtip`() =
		runBlocking {
			coEvery {
				helper["doInstall"](any<Context>(), any<AssetsInstallerProgressConsumer>())
			} throws FileNotFoundException("data/common/gradle.zip.br")

			val failure = assertMissingAssetFailure(helper.install(ctx))

			assertFalse("Should skip GlitchTip report", failure.shouldReportToGlitchTip)
		}

	@Test
	fun `install reports Failure when doInstall's own preInstall catch block swallows an exception`() =
		runBlocking {
			// Unlike the test above (which mocks doInstall itself to throw, bypassing its
			// internal try/catch entirely), this lets the real doInstall() run and only
			// stubs the underlying installer's preInstall, so it actually exercises the
			// catch-then-rethrow path inside doInstall -- the path ADFA-5037 found silently
			// swallowing failures by returning a Result.Failure value instead of throwing,
			// which runCatching in install() can't observe.
			//
			// This relies on AssetsInstaller.CURRENT_INSTALLER resolving to SplitAssetsInstaller,
			// which only holds for debug builds (see AssetsInstaller.kt's USE_BUNDLED_ASSETS).
			// Run via :app:testV8DebugUnitTest, which satisfies that -- a bare aggregate `test`
			// task fanning out to other build variants would silently bypass this stub instead
			// of exercising the intended code path.
			mockkObject(IDEBuildConfigProvider.Companion)
			mockkStatic(Brotli4jLoader::class)
			mockkObject(SplitAssetsInstaller)

			// doInstall() looks up the build's CpuArch before reaching preInstall; the
			// real IDEBuildConfigProviderImpl needs a live BaseApplication instance to
			// do that, which isn't available in this unit test, so stub it directly.
			val buildConfigProvider = mockk<IDEBuildConfigProvider>(relaxed = true)
			every { buildConfigProvider.cpuArch } returns CpuArch.AARCH64
			every { IDEBuildConfigProvider.getInstance() } returns buildConfigProvider

			// doInstall() also loads the Brotli native library before reaching
			// preInstall; it isn't available in this unit test either.
			every { Brotli4jLoader.ensureAvailability() } just Runs

			val stagingDirSlot = slot<Path>()
			coEvery {
				SplitAssetsInstaller.preInstall(any(), capture(stagingDirSlot))
			} throws FileNotFoundException("assets-arm64-v8a.zip")

			// Stubbed (rather than left to call the real implementation) because the
			// real postInstall() chmods paths under Environment.BUILD_TOOLS_DIR, which
			// requires Environment.init() -- unrelated to what this test verifies.
			coEvery {
				SplitAssetsInstaller.postInstall(any(), any())
			} just Runs

			assertMissingAssetFailure(helper.install(ctx))

			// A preInstall failure must not skip the symmetric cleanup that a
			// successful install would get: postInstall() (closes installer
			// resources) and deleting the staging directory.
			coVerify(exactly = 1) { SplitAssetsInstaller.postInstall(any(), any()) }
			assertFalse(
				"Expected staging directory to be deleted even though preInstall failed",
				Files.exists(stagingDirSlot.captured),
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
