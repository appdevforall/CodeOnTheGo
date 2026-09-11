package com.itsaky.androidide.repositories

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateCollectionRepositoryImplTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var repository: TemplateCollectionRepository
	private lateinit var templatesDir: File
	private val previousTemplatesDir: File? = Environment.TEMPLATES_DIR

	@Before
	fun setup() {
		repository = TemplateCollectionRepositoryImpl()
		templatesDir = tempFolder.newFolder("templates")
		Environment.TEMPLATES_DIR = templatesDir
	}

	@After
	fun tearDown() {
		Environment.TEMPLATES_DIR = previousTemplatesDir
	}

	private fun buildCgt(
		name: String,
		outputDir: File = tempFolder.newFolder(),
	): File =
		CgtTemplateBuilder(name)
			.description("A test template")
			// ZipTemplateReader.read() fully builds a ProjectTemplate (not just metadata) and,
			// absent this, falls back to Environment.PROJECTS_DIR - null outside a real app setup.
			.defaultSaveLocation(tempFolder.newFolder().absolutePath)
			.build(outputDir)

	@Test
	fun `isTemplatesFeatureAvailable is true when TEMPLATES_DIR is set`() {
		assertThat(repository.isTemplatesFeatureAvailable()).isTrue()
	}

	@Test
	fun `isTemplatesFeatureAvailable is false when TEMPLATES_DIR is null`() {
		Environment.TEMPLATES_DIR = null
		assertThat(repository.isTemplatesFeatureAvailable()).isFalse()
	}

	@Test
	fun `inspectCollection returns template names for a valid archive`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.inspectCollection(cgt)

			assertThat(result.isSuccess).isTrue()
			assertThat(result.getOrNull()?.templateNames).containsExactly("Empty Activity")
		}

	@Test
	fun `inspectCollection fails for a corrupted archive`() =
		runTest {
			val corrupted = File(tempFolder.newFolder(), "broken.cgt")
			corrupted.writeText("not a zip file")

			val result = repository.inspectCollection(corrupted)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `findExistingCollision matches an installed collection case-insensitively`() =
		runTest {
			File(templatesDir, "MyTemplates.cgt").writeText("placeholder")

			val match = repository.findExistingCollision("mytemplates")

			assertThat(match).isEqualTo("MyTemplates")
		}

	@Test
	fun `findExistingCollision matches an uppercase CGT extension`() =
		runTest {
			File(templatesDir, "MyTemplates.CGT").writeText("placeholder")

			val match = repository.findExistingCollision("mytemplates")

			assertThat(match).isEqualTo("MyTemplates")
		}

	@Test
	fun `findExistingCollision returns null when there is no match`() =
		runTest {
			val match = repository.findExistingCollision("does-not-exist")

			assertThat(match).isNull()
		}

	@Test
	fun `installCollection copies the archive into TEMPLATES_DIR and deletes the source`() =
		runTest {
			val cgt = buildCgt("Empty Activity")
			val expectedBytes = cgt.readBytes()

			val result = repository.installCollection(cgt, "my-templates", overwrite = false)

			assertThat(result.isSuccess).isTrue()
			val installed = File(templatesDir, "my-templates.cgt")
			assertThat(installed.exists()).isTrue()
			assertThat(installed.readBytes()).isEqualTo(expectedBytes)
			assertThat(cgt.exists()).isFalse()
		}

	@Test
	fun `installCollection without overwrite fails when the destination already exists`() =
		runTest {
			File(templatesDir, "my-templates.cgt").writeText("existing")
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "my-templates", overwrite = false)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `installCollection without overwrite fails against an existing case-variant destination`() =
		runTest {
			File(templatesDir, "MyTemplates.CGT").writeText("existing")
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "mytemplates", overwrite = false)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `installCollection with overwrite replaces an existing case-variant destination in place`() =
		runTest {
			val destination = File(templatesDir, "MyTemplates.CGT")
			destination.writeText("stale content")
			val cgt = buildCgt("Empty Activity")
			val expectedBytes = cgt.readBytes()

			val result = repository.installCollection(cgt, "mytemplates", overwrite = true)

			assertThat(result.isSuccess).isTrue()
			assertThat(destination.readBytes()).isEqualTo(expectedBytes)
		}

	@Test
	fun `installCollection with overwrite replaces the existing destination`() =
		runTest {
			val destination = File(templatesDir, "my-templates.cgt")
			destination.writeText("stale content")
			val cgt = buildCgt("Empty Activity")
			val expectedBytes = cgt.readBytes()

			val result = repository.installCollection(cgt, "my-templates", overwrite = true)

			assertThat(result.isSuccess).isTrue()
			assertThat(destination.readBytes()).isEqualTo(expectedBytes)
		}

	@Test
	fun `installCollection refuses to replace the reserved bundled core archive, even with overwrite`() =
		runTest {
			val bundledCore = File(templatesDir, "core.cgt")
			bundledCore.writeText("bundled default templates")
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "core", overwrite = true)

			assertThat(result.isFailure).isTrue()
			assertThat(bundledCore.readText()).isEqualTo("bundled default templates")
		}

	@Test
	fun `installCollection refuses a reserved name case-insensitively`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "CORE", overwrite = true)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `installCollection rejects a targetBaseName containing a path separator`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "../evil", overwrite = false)

			assertThat(result.isFailure).isTrue()
			assertThat(File(templatesDir.parentFile, "evil.cgt").exists()).isFalse()
		}

	@Test
	fun `installCollection rejects a targetBaseName that is a bare traversal segment`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "..", overwrite = false)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `installCollection rejects a targetBaseName containing a backslash`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "evil\\name", overwrite = false)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `installCollection rejects a bare dot targetBaseName`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, ".", overwrite = false)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `installCollection rejects a blank targetBaseName`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "   ", overwrite = false)

			assertThat(result.isFailure).isTrue()
		}

	@Test
	fun `installCollection preserves the existing collection if the incoming archive cannot be staged`() =
		runTest {
			val destination = File(templatesDir, "my-templates.cgt")
			destination.writeText("stale but valid content")
			// A candidate that no longer exists can't be copied into staging, so the staging
			// step fails before destFile is ever touched.
			val missingCandidate = File(tempFolder.newFolder(), "gone.cgt")

			val result = repository.installCollection(missingCandidate, "my-templates", overwrite = true)

			assertThat(result.isFailure).isTrue()
			assertThat(destination.exists()).isTrue()
			assertThat(destination.readText()).isEqualTo("stale but valid content")
		}

	@Test
	fun `installCollection leaves candidateFile untouched so the caller can retry after a failure`() =
		runTest {
			// A reserved-name failure happens before any file I-O, so candidateFile must still be
			// exactly where the caller left it - this is the contract ExternalFileInstallViewModel
			// relies on to keep the retry dialog usable after a failed install.
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "core", overwrite = true)

			assertThat(result.isFailure).isTrue()
			assertThat(cgt.exists()).isTrue()
		}

	@Test
	fun `installCollection leaves no stray staging or backup files behind on a fresh install`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "my-templates", overwrite = false)

			assertThat(result.isSuccess).isTrue()
			assertThat(templatesDir.listFiles()?.map { it.name }).containsExactly("my-templates.cgt")
		}

	@Test
	fun `installCollection leaves no stray staging or backup files behind on an overwrite`() =
		runTest {
			File(templatesDir, "my-templates.cgt").writeText("stale content")
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "my-templates", overwrite = true)

			assertThat(result.isSuccess).isTrue()
			assertThat(templatesDir.listFiles()?.map { it.name }).containsExactly("my-templates.cgt")
		}
}
