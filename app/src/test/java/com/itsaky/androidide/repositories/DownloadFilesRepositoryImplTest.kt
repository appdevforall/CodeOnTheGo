package com.itsaky.androidide.repositories

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadFilesRepositoryImplTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private fun file(
		name: String,
		modified: Long,
		dir: File = tempFolder.root,
	): File =
		File(dir, name).apply {
			writeText("x")
			setLastModified(modified)
		}

	@Test
	fun `lists only files with the extension, ignoring case`() =
		runTest {
			file("a.cgp", 1_000_000)
			file("B.CGP", 2_000_000)
			file("c.cgt", 3_000_000)
			file("d.apk", 4_000_000)
			file("e.cgp.zip", 5_000_000)

			val names = DownloadFilesRepositoryImpl(tempFolder.root).list("cgp").map { it.name }

			assertThat(names).containsExactly("a.cgp", "B.CGP")
		}

	@Test
	fun `newest file comes first`() =
		runTest {
			file("old.cgt", 1_000_000)
			file("new.cgt", 3_000_000)
			file("mid.cgt", 2_000_000)

			val names = DownloadFilesRepositoryImpl(tempFolder.root).list("cgt").map { it.name }

			assertThat(names).containsExactly("new.cgt", "mid.cgt", "old.cgt").inOrder()
		}

	@Test
	fun `ignores subfolders and directories named like an add-on`() =
		runTest {
			file("top.cgp", 1_000_000)
			file("nested.cgp", 2_000_000, dir = tempFolder.newFolder("templates"))
			tempFolder.newFolder("folder.cgp")

			val names = DownloadFilesRepositoryImpl(tempFolder.root).list("cgp").map { it.name }

			assertThat(names).containsExactly("top.cgp")
		}

	@Test
	fun `reports size and date`() =
		runTest {
			val f = File(tempFolder.root, "p.cgp").apply { writeBytes(ByteArray(1234)) }
			f.setLastModified(7_000_000)

			val listed = DownloadFilesRepositoryImpl(tempFolder.root).list("cgp").single()

			assertThat(listed.sizeBytes).isEqualTo(1234)
			assertThat(listed.lastModified).isEqualTo(7_000_000)
		}

	@Test
	fun `missing folder gives an empty list`() =
		runTest {
			val missing = File(tempFolder.root, "no-such-dir")

			assertThat(DownloadFilesRepositoryImpl(missing).list("cgp")).isEmpty()
		}
}
