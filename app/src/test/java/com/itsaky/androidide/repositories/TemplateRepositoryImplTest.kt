package com.itsaky.androidide.repositories

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Install copies (the Downloads original stays), uninstall deletes (nothing is restored), and
 * neither touches the other directory. [ITemplateProvider.getInstance] - a ServiceLoader-backed
 * singleton not wired up on the unit test classpath - is stubbed so the success paths run.
 *
 * Runs under Robolectric rather than plain JUnit4 because the `listTemplateFiles` cases build
 * real `.cgt` archives, and parsing one reaches `org.json.JSONObject` - a "not mocked" stub
 * under plain `android.jar`.
 */
@RunWith(RobolectricTestRunner::class)
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
		mockkObject(ITemplateProvider.Companion)
		every { ITemplateProvider.getInstance(any()) } returns mockk(relaxed = true)
	}

	@After
	fun tearDown() {
		unmockkObject(ITemplateProvider.Companion)
		// Undo any permission changes a test made, or TemporaryFolder can't clean up after itself.
		templatesDir.setWritable(true)
		downloadDir.setWritable(true)
	}

	private fun item(
		file: File,
		installed: Boolean,
		provenance: TemplateProvenance = TemplateProvenance.USER,
	) = CgtFileItem(
		file = file,
		name = file.name,
		templates = listOf(TemplateMetadata("T", "d", "1.0")),
		installed = installed,
		provenance = provenance,
	)

	@Test
	fun installTemplate_copiesIntoTheStore_andLeavesTheDownloadsOriginal() =
		runTest {
			val source = File(downloadDir, "install.cgt").apply { writeText("source") }

			val result = repository.installTemplate(item(source, installed = false))

			assertThat(result.isSuccess).isTrue()
			assertThat(File(templatesDir, "install.cgt").readText()).isEqualTo("source")
			assertThat(source.readText()).isEqualTo("source")
		}

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
	fun uninstallTemplate_deletesTheInstalledCopy_withoutWritingToDownloads() =
		runTest {
			// Both provenances behave the same (ADFA-5444): nothing is restored for either.
			for (provenance in TemplateProvenance.entries) {
				val installed = File(templatesDir, "uninstall_$provenance.cgt").apply { writeText("installed") }

				val result = repository.uninstallTemplate(item(installed, installed = true, provenance = provenance))

				assertThat(result.isSuccess).isTrue()
				assertThat(installed.exists()).isFalse()
			}
			assertThat(downloadDir.list()).isEmpty()
		}

	@Test
	fun uninstallTemplate_succeeds_whenTheDownloadsOriginalIsStillThere() =
		runTest {
			// The normal state after a copy-install - the old restore path failed here on its
			// "already exists in Downloads" check, which is what made uninstall look broken.
			val installed = File(templatesDir, "dup.cgt").apply { writeText("installed") }
			val original = File(downloadDir, "dup.cgt").apply { writeText("original") }

			val result = repository.uninstallTemplate(item(installed, installed = true))

			assertThat(result.isSuccess).isTrue()
			assertThat(installed.exists()).isFalse()
			assertThat(original.readText()).isEqualTo("original")
		}

	@Test
	fun uninstallTemplate_deleteFails_reportsIOException_andKeepsTheInstalledCopy() =
		runTest {
			val installed = File(templatesDir, "uninstall.cgt").apply { writeText("installed") }

			// File.delete() needs write permission on the *parent directory*, not the file itself.
			check(templatesDir.setWritable(false)) { "test setup: could not make templatesDir read-only" }

			val result = repository.uninstallTemplate(item(installed, installed = true))

			assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
			assertThat(installed.exists()).isTrue()
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

	/** Writes a minimal but genuinely parseable .cgt carrying one template. */
	private fun writeCgt(
		dir: File,
		fileName: String,
	): File {
		val file = File(dir, fileName)
		ZipOutputStream(file.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("Sample/template/template.json"))
			zip.write("""{"name":"Sample","description":"d","version":"1.0"}""".toByteArray(Charsets.UTF_8))
			zip.closeEntry()
		}
		return file
	}

	@Test
	fun listTemplateFiles_hidesTheDownloadedTwinOfAnInstalledArchive() =
		runTest {
			writeCgt(templatesDir, "dup.cgt")
			writeCgt(downloadDir, "dup.cgt")

			val items = repository.listTemplateFiles().getOrThrow()

			// One row, not two identical-looking ones - the Downloads twin's Install could only
			// ever fail, since installTemplate refuses to overwrite.
			assertThat(items).hasSize(1)
			assertThat(items.single().installed).isTrue()
		}

	@Test
	fun listTemplateFiles_matchesTwinNamesCaseInsensitively() =
		runTest {
			writeCgt(templatesDir, "Dup.cgt")
			writeCgt(downloadDir, "dup.cgt")

			val items = repository.listTemplateFiles().getOrThrow()

			assertThat(items).hasSize(1)
			assertThat(items.single().installed).isTrue()
		}

	@Test
	fun listTemplateFiles_keepsDownloadsThatAreNotTwins() =
		runTest {
			writeCgt(templatesDir, "installed.cgt")
			writeCgt(downloadDir, "other.cgt")

			val items = repository.listTemplateFiles().getOrThrow()

			// Shadowing must be keyed on the name, not applied to every download.
			assertThat(items.map { it.name }).containsExactly("installed.cgt", "other.cgt")
			assertThat(items.filter { it.installed }.map { it.name }).containsExactly("installed.cgt")
		}

	@Test
	fun listTemplateFiles_excludesTheBundledCoreArchive() =
		runTest {
			writeCgt(templatesDir, TEMPLATE_CORE_ARCHIVE)
			writeCgt(templatesDir, "installed.cgt")

			val items = repository.listTemplateFiles().getOrThrow()

			assertThat(items.map { it.name }).containsExactly("installed.cgt")
		}

	@Test
	fun listTemplateFiles_excludesTheCoreArchiveCaseInsensitively() =
		runTest {
			writeCgt(templatesDir, "Core.CGT")
			writeCgt(templatesDir, "installed.cgt")

			val items = repository.listTemplateFiles().getOrThrow()

			assertThat(items.map { it.name }).containsExactly("installed.cgt")
		}

	@Test
	fun uninstallTemplate_refusesTheCoreArchive_evenWhenNotReachedThroughTheList() =
		runTest {
			// A differently-cased name, built directly rather than via listTemplateFiles: the guard
			// must hold on the operation itself, not rely on the list having hidden the item.
			val core = File(templatesDir, "Core.cgt").apply { writeText("bundled") }

			val result = repository.uninstallTemplate(item(core, installed = true))

			assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
			assertThat(core.readText()).isEqualTo("bundled")
			assertThat(downloadDir.list()).isEmpty()
		}

	@Test
	fun listTemplateFiles_excludesACoreArchiveFoundOnlyInDownloads() =
		runTest {
			// No core.cgt in templatesDir yet (e.g. asset bootstrap hasn't run) - the twin-hiding
			// dedup above can't catch this case, since there's no installed name to match against.
			// core.cgt must still be excluded outright, not shown as an installable USER template.
			writeCgt(downloadDir, TEMPLATE_CORE_ARCHIVE)

			val items = repository.listTemplateFiles().getOrThrow()

			assertThat(items).isEmpty()
		}
}
