package com.itsaky.androidide.repositories

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.io.IOException

/**
 * Pins the two riskiest branches in [TemplateRepositoryImpl.installTemplate]/
 * [TemplateRepositoryImpl.uninstallTemplate]: a name collision must fail without touching either
 * copy, and a failed delete after a successful copy must roll back to leave exactly one copy
 * behind. Both are reachable without [com.itsaky.androidide.templates.ITemplateProvider], which is
 * only touched on the success path (`ITemplateProvider.getInstance(reload = true)`, a
 * ServiceLoader-backed singleton not wired up on the unit test classpath) - so a happy-path
 * install/uninstall test is intentionally not included here.
 */
@RunWith(JUnit4::class)
class TemplateRepositoryImplTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var templatesDir: File
	private lateinit var downloadDir: File
	private lateinit var repository: TemplateRepositoryImpl

	@Before
	fun setup() {
		templatesDir = tempFolder.newFolder("templates")
		downloadDir = tempFolder.newFolder("downloads")
		repository = TemplateRepositoryImpl(templatesDir, downloadDir)
	}

	@After
	fun tearDown() {
		// Undo any permission changes a test made, or TemporaryFolder can't clean up after itself.
		templatesDir.setWritable(true)
		downloadDir.setWritable(true)
	}

	private fun item(
		file: File,
		installed: Boolean,
	) = CgtFileItem(
		file = file,
		name = file.name,
		templates = listOf(TemplateMetadata("T", "d", "1.0")),
		installed = installed,
		provenance = TemplateProvenance.USER,
	)

	@Test
	fun installTemplate_nameCollision_failsWithoutTouchingEitherCopy() =
		runTest {
			val source = File(downloadDir, "dup.cgt").apply { writeText("source") }
			val existingDest = File(templatesDir, "dup.cgt").apply { writeText("already installed") }

			val result = repository.installTemplate(item(source, installed = false))

			assertThat(result.isFailure).isTrue()
			assertThat(source.exists()).isTrue()
			assertThat(source.readText()).isEqualTo("source")
			assertThat(existingDest.readText()).isEqualTo("already installed")
		}

	@Test
	fun installTemplate_deleteFails_rollsBackAndLeavesExactlyOneCopy() =
		runTest {
			val source = File(downloadDir, "install.cgt").apply { writeText("source") }
			val dest = File(templatesDir, "install.cgt")

			// File.delete() needs write permission on the *parent directory*, not the file
			// itself - this is what makes item.file.delete() fail after copyTo() already
			// succeeded (dest is in the unaffected templatesDir).
			check(downloadDir.setWritable(false)) { "test setup: could not make downloadDir read-only" }

			val result = repository.installTemplate(item(source, installed = false))

			assertThat(result.isFailure).isTrue()
			assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
			assertThat(source.exists()).isTrue()
			assertThat(dest.exists()).isFalse()
		}

	@Test
	fun uninstallTemplate_nameCollision_failsWithoutTouchingEitherCopy() =
		runTest {
			val source = File(templatesDir, "dup.cgt").apply { writeText("installed") }
			val existingDownload = File(downloadDir, "dup.cgt").apply { writeText("already in downloads") }

			val result = repository.uninstallTemplate(item(source, installed = true))

			assertThat(result.isFailure).isTrue()
			assertThat(source.exists()).isTrue()
			assertThat(source.readText()).isEqualTo("installed")
			assertThat(existingDownload.readText()).isEqualTo("already in downloads")
		}

	@Test
	fun uninstallTemplate_deleteFails_rollsBackAndLeavesExactlyOneCopy() =
		runTest {
			val source = File(templatesDir, "uninstall.cgt").apply { writeText("installed") }
			val restored = File(downloadDir, "uninstall.cgt")

			check(templatesDir.setWritable(false)) { "test setup: could not make templatesDir read-only" }

			val result = repository.uninstallTemplate(item(source, installed = true))

			assertThat(result.isFailure).isTrue()
			assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
			assertThat(source.exists()).isTrue()
			assertThat(restored.exists()).isFalse()
		}

	@Test
	fun deleteDownloadFile_succeeds_whenNotInstalled() =
		runTest {
			val file = File(downloadDir, "unused.cgt").apply { writeText("x") }

			val result = repository.deleteDownloadFile(item(file, installed = false))

			assertThat(result.isSuccess).isTrue()
			assertThat(file.exists()).isFalse()
		}

	@Test
	fun deleteDownloadFile_fails_whenInstalled() =
		runTest {
			val file = File(templatesDir, "installed.cgt").apply { writeText("x") }

			val result = repository.deleteDownloadFile(item(file, installed = true))

			assertThat(result.isFailure).isTrue()
			assertThat(file.exists()).isTrue()
		}

	@Test
	fun listTemplateFiles_partitionsByDirectory_andSkipsUnparsableArchives() =
		runTest {
			File(templatesDir, "not-a-zip.cgt").writeText("garbage")

			val result = repository.listTemplateFiles()

			assertThat(result.isSuccess).isTrue()
			// The malformed .cgt has no template.json and is silently skipped, not surfaced as
			// a failure - see TemplateRepositoryImpl.parseCgtFile.
			assertThat(result.getOrThrow()).isEmpty()
		}
}
