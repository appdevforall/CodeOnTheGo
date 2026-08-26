package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
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
		val result = ZipUtils.unzipFile(zipFile, destDir)

		assertThat(File(destDir, "dir/nested.txt").readText()).isEqualTo("nested content")
		assertThat(File(destDir, "root.txt").readText()).isEqualTo("root content")
		assertThat(result.skipped).isEmpty()
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
	fun `unzipFile skips an entry that would extract over an existing symlink, without aborting the rest`() {
		val destDir = tempFolder.newFolder("dest")
		val realFile = File(destDir, "real.txt").apply { writeText("original") }
		val linkPath = File(destDir, "link.txt").toPath()
		createSymlinkOrSkipTest(linkPath, realFile.toPath())

		// The symlink's target is inside destDir, so the canonical-path containment check alone
		// would pass -- this isolates the separate, explicit isSymbolicLink guard. A second,
		// unrelated entry proves a skip doesn't abort the whole archive (e.g. a user's legitimately
		// symlinked gradlew alongside a normal Gradle wrapper zip entry).
		val zipFile = tempFolder.newFile("archive.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("link.txt"))
			zip.write("payload".toByteArray())
			zip.closeEntry()

			zip.putNextEntry(ZipEntry("unrelated.txt"))
			zip.write("unrelated content".toByteArray())
			zip.closeEntry()
		}

		val result = ZipUtils.unzipFile(zipFile, destDir)

		assertThat(Files.isSymbolicLink(linkPath)).isTrue()
		assertThat(realFile.readText()).isEqualTo("original")
		assertThat(File(destDir, "unrelated.txt").readText()).isEqualTo("unrelated content")
		assertThat(result.extracted.map { it.name }).containsExactly("unrelated.txt")
		assertThat(result.skipped).containsExactly("link.txt")
	}

	// Regression test: a *dangling* symlink at an entry's target used to abort the whole archive --
	// the resolver refuses a link it cannot prove contained, and the escape exception fired. The
	// link is lexically inside destDir and nothing is written at or through it, so this is the same
	// leave-the-user's-symlink-alone skip as above, reported the same way.
	@Test
	fun `unzipFile skips an entry whose target is a dangling symlink inside the destination`() {
		val destDir = tempFolder.newFolder("dest")
		val linkPath = File(destDir, "link.txt").toPath()
		createSymlinkOrSkipTest(linkPath, File(destDir, "missing-target.txt").toPath())

		val zipFile = tempFolder.newFile("archive.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("link.txt"))
			zip.write("payload".toByteArray())
			zip.closeEntry()

			zip.putNextEntry(ZipEntry("unrelated.txt"))
			zip.write("unrelated content".toByteArray())
			zip.closeEntry()
		}

		val result = ZipUtils.unzipFile(zipFile, destDir)

		assertThat(Files.isSymbolicLink(linkPath)).isTrue()
		assertThat(File(destDir, "missing-target.txt").exists()).isFalse()
		assertThat(File(destDir, "unrelated.txt").readText()).isEqualTo("unrelated content")
		assertThat(result.extracted.map { it.name }).containsExactly("unrelated.txt")
		assertThat(result.skipped).containsExactly("link.txt")
	}

	@Test
	fun `unzipFile allows a harmless double-dot inside a path segment`() {
		val zipFile = tempFolder.newFile("archive.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("notes..txt"))
			zip.write("note content".toByteArray())
			zip.closeEntry()

			zip.putNextEntry(ZipEntry("a..b/c.txt"))
			zip.write("nested content".toByteArray())
			zip.closeEntry()
		}

		val destDir = tempFolder.newFolder("dest")
		ZipUtils.unzipFile(zipFile, destDir)

		assertThat(File(destDir, "notes..txt").readText()).isEqualTo("note content")
		assertThat(File(destDir, "a..b/c.txt").readText()).isEqualTo("nested content")
	}

	// Regression: the symlink-skip fallback must not resurrect an entry the resolver rejected for
	// its syntax. "a/../link.txt" normalizes to an existing symlink inside destDir, so without the
	// lexical reject in the fallback the entry was silently skipped instead of failing the archive.
	@Test
	fun `unzipFile rejects a dot-dot entry even when a symlink sits at its normalized target`() {
		val destDir = tempFolder.newFolder("dest")
		val realFile = File(destDir, "real.txt").apply { writeText("original") }
		val linkPath = File(destDir, "link.txt").toPath()
		createSymlinkOrSkipTest(linkPath, realFile.toPath())

		val zipFile = tempFolder.newFile("evil.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("a/../link.txt"))
			zip.write("payload".toByteArray())
			zip.closeEntry()
		}

		val thrown = assertThrows(IOException::class.java) { ZipUtils.unzipFile(zipFile, destDir) }
		assertThat(thrown).hasMessageThat().contains("does not resolve to a safe path")
		assertThat(Files.isSymbolicLink(linkPath)).isTrue()
		assertThat(realFile.readText()).isEqualTo("original")
	}

	// Regression: the symlink-skip fallback used to stat the entry's *normalized* path, which
	// follows an ancestor symlink -- with dest/a -> outside, entry "a/link.txt" stat'ed
	// outside/link.txt, found a symlink there, and the escaping archive was silently skipped
	// instead of rejected. The skip is only for a link whose every ancestor is a real directory
	// inside destDir.
	@Test
	fun `unzipFile rejects an entry that reaches a symlink through a symlinked ancestor`() {
		val destDir = tempFolder.newFolder("dest")
		val outsideDir = tempFolder.newFolder("outside")
		val outsideTarget = File(outsideDir, "target.txt").apply { writeText("outside content") }
		createSymlinkOrSkipTest(File(outsideDir, "link.txt").toPath(), outsideTarget.toPath())
		createSymlinkOrSkipTest(File(destDir, "a").toPath(), outsideDir.toPath())

		val zipFile = tempFolder.newFile("evil.zip")
		ZipOutputStream(zipFile.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("a/link.txt"))
			zip.write("payload".toByteArray())
			zip.closeEntry()
		}

		val thrown = assertThrows(IOException::class.java) { ZipUtils.unzipFile(zipFile, destDir) }
		assertThat(thrown).hasMessageThat().contains("does not resolve to a safe path")
		assertThat(outsideTarget.readText()).isEqualTo("outside content")
	}

	// An entry name the platform cannot turn into a path is a broken archive, not a crash: the
	// resolver reports it as unresolvable (null), and unzipFile turns that into the one IOException
	// it declares, rather than an unchecked InvalidPathException escaping.
	@Test
	fun `unzipFile reports an entry whose name is not a usable path`() {
		val zip = tempFolder.newFile("bad-name.zip")
		ZipOutputStream(zip.outputStream()).use { out ->
			out.putNextEntry(ZipEntry("bad\u0000name.txt"))
			out.write("x".toByteArray())
			out.closeEntry()
		}

		val destDir = tempFolder.newFolder("out-bad-name")
		val thrown = assertThrows(IOException::class.java) { ZipUtils.unzipFile(zip, destDir) }
		assertThat(thrown).hasMessageThat().contains("does not resolve to a safe path")
	}

	// The symlink policy above the write is a stat, and the write is a separate open: a link that
	// appears in between is followed, because FileOutputStream resolves links. This pins the
	// enforcement that closes that window -- O_NOFOLLOW in the open itself. It is tested directly
	// because the policy check means the race is the only way to reach it in normal extraction, and
	// a race is not something a test can stage reliably.
	@Test
	fun `writing refuses to follow a symlink at the target path`() {
		val dir = tempFolder.newFolder("nofollow")
		val outside = File(dir, "outside.txt").apply { writeText("original") }
		val target = File(dir, "target.txt")
		createSymlinkOrSkipTest(target.toPath(), outside.toPath())

		val thrown =
			assertThrows(IOException::class.java) {
				ByteArrayInputStream("payload".toByteArray()).use { ZipUtils.writeNoFollow(target, it) }
			}

		assertThat(thrown).isNotNull()
		// The link's destination is untouched: nothing was written through it.
		assertThat(outside.readText()).isEqualTo("original")
	}
}
