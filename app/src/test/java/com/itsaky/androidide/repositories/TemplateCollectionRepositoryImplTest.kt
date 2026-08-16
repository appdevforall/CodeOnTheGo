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
	fun `findExistingCollision returns null when there is no match`() =
		runTest {
			val match = repository.findExistingCollision("does-not-exist")

			assertThat(match).isNull()
		}

	@Test
	fun `installCollection copies the archive into TEMPLATES_DIR and deletes the source`() =
		runTest {
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "my-templates", overwrite = false)

			assertThat(result.isSuccess).isTrue()
			assertThat(File(templatesDir, "my-templates.cgt").exists()).isTrue()
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
	fun `installCollection with overwrite replaces the existing destination`() =
		runTest {
			val destination = File(templatesDir, "my-templates.cgt")
			destination.writeText("stale content")
			val cgt = buildCgt("Empty Activity")

			val result = repository.installCollection(cgt, "my-templates", overwrite = true)

			assertThat(result.isSuccess).isTrue()
			assertThat(destination.readText()).isNotEqualTo("stale content")
		}
}
