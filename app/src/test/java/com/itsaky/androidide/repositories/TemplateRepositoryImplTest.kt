package com.itsaky.androidide.repositories

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.test.runTest
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
 * Pins the riskiest branches in [TemplateRepositoryImpl.installTemplate]/
 * [TemplateRepositoryImpl.uninstallTemplate]: a name collision on install must fail without
 * touching either copy, a failed delete after a successful copy must roll back to leave exactly
 * one copy behind, and (ADFA-5446) uninstall must surface a [DownloadFileConflictException] -
 * without touching either copy - when Downloads already has a same-named file and the caller
 * hasn't opted into overwriting it.
 *
 * Runs under Robolectric rather than plain JUnit4 for two reasons: the `listTemplateFiles` cases
 * build real `.cgt` archives, and parsing one reaches `org.json.JSONObject` - a "not mocked" stub
 * under plain `android.jar`; and any test that reaches a success path touches
 * `ITemplateProvider.getInstance(reload = true)`, a ServiceLoader-backed singleton whose default
 * implementation ([com.itsaky.androidide.templates.impl.TemplateProviderImpl]) reads
 * [Environment.TEMPLATES_DIR] directly - `setup()` points that at [templatesDir] so it resolves
 * instead of NPEing.
 */
@RunWith(RobolectricTestRunner::class)
class TemplateRepositoryImplTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var templatesDir: File
	private lateinit var downloadDir: File
	private lateinit var repository: TemplateRepositoryImpl
	private val previousTemplatesDir: File? = Environment.TEMPLATES_DIR

	@Before
	fun setup() {
		templatesDir = tempFolder.newFolder("templates")
		downloadDir = tempFolder.newFolder("downloads")
		repository = TemplateRepositoryImpl(ApplicationProvider.getApplicationContext(), templatesDir, downloadDir)
		// ITemplateProvider.getInstance(reload = true) - hit on every install/uninstall success
		// path - resolves the real TemplateProviderImpl via ServiceLoader, which reads this
		// directly rather than taking it as a constructor argument; leaving it null NPEs inside
		// TemplateProviderImpl.initializeTemplates().
		Environment.TEMPLATES_DIR = templatesDir
	}

	@After
	fun tearDown() {
		// Undo any permission changes a test made, or TemporaryFolder can't clean up after itself.
		templatesDir.setWritable(true)
		downloadDir.setWritable(true)
		Environment.TEMPLATES_DIR = previousTemplatesDir
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

	/**
	 * ADFA-5446: a same-named file can already be sitting in Downloads without the user ever
	 * installing anything through this repository - e.g. TemplateCollectionRepository's "open a
	 * .cgt from outside the app" path deliberately never deletes the file the user opened. Uninstall
	 * used to hard-fail here ("already exists in Downloads") with no way to proceed, which was
	 * doubly confusing since scanTemplates' dedup means the user never even saw that Downloads copy
	 * in the list to explain the error. It must now surface a [DownloadFileConflictException]
	 * instead - without touching either copy - so the caller can ask the user before overwriting.
	 */
	@Test
	fun uninstallTemplate_downloadsAlreadyHasACopy_failsWithConflictWithoutOverwrite() =
		runTest {
			val source = File(templatesDir, "dup.cgt").apply { writeText("installed") }
			val existingDownload = File(downloadDir, "dup.cgt").apply { writeText("already in downloads") }

			val result = repository.uninstallTemplate(item(source, installed = true))

			assertThat(result.isFailure).isTrue()
			assertThat(result.exceptionOrNull()).isInstanceOf(DownloadFileConflictException::class.java)
			assertThat(source.exists()).isTrue()
			assertThat(existingDownload.readText()).isEqualTo("already in downloads")
		}

	/** The user-confirmed retry: overwrite = true clobbers the Downloads twin and completes the uninstall. */
	@Test
	fun uninstallTemplate_overwriteTrue_replacesTheExistingDownload() =
		runTest {
			val source = File(templatesDir, "dup.cgt").apply { writeText("installed") }
			val existingDownload = File(downloadDir, "dup.cgt").apply { writeText("stale copy") }

			val result = repository.uninstallTemplate(item(source, installed = true), overwrite = true)

			assertThat(result.isSuccess).isTrue()
			assertThat(source.exists()).isFalse()
			assertThat(existingDownload.readText()).isEqualTo("installed")
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
}
