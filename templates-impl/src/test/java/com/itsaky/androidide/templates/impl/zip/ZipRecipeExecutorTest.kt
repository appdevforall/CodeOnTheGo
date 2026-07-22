package com.itsaky.androidide.templates.impl.zip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.ModuleType
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectVersionData
import com.itsaky.androidide.templates.RecipeExecutor
import com.itsaky.androidide.templates.TestRecipeExecutor
import com.itsaky.androidide.utils.Environment
import org.adfa.constants.Sdk
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class ZipRecipeExecutorTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var projectDir: File

	@Before
	fun setup() {
		projectDir = File(tempFolder.root, "TestApp")
		// point at non-existent files so keystore() is a no-op
		Environment.KEYSTORE_RELEASE = File(tempFolder.root, "release.keystore")
		Environment.KEYSTORE_PROPERTIES = File(tempFolder.root, "release.properties")
	}

	@Test
	fun `valid template renders and creates the project`() {
		val zip = buildZip(mapOf("tpl/hello.txt.peb" to "Hello \${{ APP_NAME }}!"))

		executor(zip).execute(recipeExecutor())

		assertThat(File(projectDir, "hello.txt").readText())
			.isEqualTo("Hello TestApp!")
	}

	@Test
	fun `cgt path and directory are exposed to templates`() {
		val zip = buildZip(mapOf("tpl/cgt.txt.peb" to "\${{ COGO_CGT_PATH }}|\${{ COGO_CGT_DIRECTORY }}"))

		executor(zip).execute(recipeExecutor())

		assertThat(File(projectDir, "cgt.txt").readText())
			.isEqualTo("${zip.absolutePath}|tpl")
	}

	@Test
	fun `pebble parse error terminates execution and removes the project dir`() {
		val zip =
			buildZip(
				mapOf(
					"tpl/good.txt.peb" to "Hello \${{ APP_NAME }}!",
					"tpl/zbad.txt.peb" to "\${% if %}",
				),
			)

		val thrown =
			assertThrows(TemplateExecutionException::class.java) {
				executor(zip).execute(recipeExecutor())
			}

		assertThat(thrown).hasMessageThat().contains("zbad.txt.peb")
		assertThat(projectDir.exists()).isFalse()
	}

	@Test
	fun `pebble evaluation error terminates execution and removes the project dir`() {
		val zip =
			buildZip(
				mapOf(
					"tpl/good.txt.peb" to "Hello \${{ APP_NAME }}!",
					"tpl/zeval.txt.peb" to "\${{ MISSING_IDENTIFIER }}",
				),
			)

		val thrown =
			assertThrows(TemplateExecutionException::class.java) {
				executor(zip).execute(recipeExecutor())
			}

		assertThat(thrown).hasMessageThat().contains("zeval.txt.peb")
		assertThat(projectDir.exists()).isFalse()
	}

	@Test
	fun `keystore copy failure does not discard the rendered project`() {
		Environment.KEYSTORE_RELEASE = tempFolder.newFile("release.keystore")
		val zip = buildZip(mapOf("tpl/hello.txt.peb" to "Hello \${{ APP_NAME }}!"))
		val failingCopyExecutor =
			object : RecipeExecutor by TestRecipeExecutor() {
				override val context: Context = ApplicationProvider.getApplicationContext()

				override fun copy(
					source: File,
					dest: File,
				): Unit = throw IOException("disk full")
			}

		val result = executor(zip).execute(failingCopyExecutor)

		assertThat(File(projectDir, "hello.txt").readText()).isEqualTo("Hello TestApp!")
		assertThat(result.hasErrorsWarnings).isTrue()
	}

	@Test
	fun `malicious basePath cannot escape the dex-opt dir`() {
		Environment.TEMPLATES_DIR = tempFolder.newFolder("ide", "templates")
		val zip =
			buildZip(
				mapOf(
					"extensions.jar" to "junk",
					"../../pwned/hello.txt.peb" to "Hello \${{ APP_NAME }}!",
				),
			)

		val result = executor(zip, basePath = "../../pwned").execute(recipeExecutor())

		// extension loading is rejected, nothing is created outside dex_opt
		assertThat(File(tempFolder.root, "ide/pwned").exists()).isFalse()
		assertThat(result.hasErrorsWarnings).isTrue()
		// rendering itself still completes
		assertThat(File(projectDir, "hello.txt").readText()).isEqualTo("Hello TestApp!")
	}

	@Test
	fun `existing project dir is left untouched`() {
		projectDir.mkdirs()
		val marker = File(projectDir, "marker.txt").apply { writeText("keep") }
		val zip = buildZip(mapOf("tpl/zbad.txt.peb" to "\${% if %}"))

		executor(zip).execute(recipeExecutor())

		assertThat(marker.readText()).isEqualTo("keep")
	}

	private fun buildZip(entries: Map<String, String>): File {
		val file = tempFolder.newFile("template.zip")
		ZipOutputStream(file.outputStream()).use { out ->
			entries.forEach { (name, content) ->
				out.putNextEntry(ZipEntry(name))
				out.write(content.toByteArray())
				out.closeEntry()
			}
		}
		return file
	}

	private fun executor(
		zip: File,
		basePath: String = "tpl",
	): ZipRecipeExecutor {
		val data =
			ProjectTemplateData(
				"TestApp",
				projectDir,
				ProjectVersionData(),
				Language.Kotlin,
				useKts = false,
			)
		val module =
			ModuleTemplateData(
				name = "app",
				appName = "TestApp",
				packageName = "com.example.app",
				projectDir = File(projectDir, "app"),
				type = ModuleType.AndroidApp,
				language = Language.Kotlin,
				minSdk = Sdk.Lollipop,
			)
		val metaJson = TemplateJson(name = "Test", description = null, version = null)
		return ZipRecipeExecutor({ ZipFile(zip) }, metaJson, mutableMapOf(), basePath, data, module)
	}

	private fun recipeExecutor(): RecipeExecutor =
		object : RecipeExecutor by TestRecipeExecutor() {
			override val context: Context = ApplicationProvider.getApplicationContext()
		}
}
