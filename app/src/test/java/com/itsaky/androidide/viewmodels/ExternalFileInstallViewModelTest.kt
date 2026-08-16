package com.itsaky.androidide.viewmodels

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.repositories.TemplateCollectionRepository
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEffect
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalFileInstallViewModelTest {
	@get:Rule
	val instantExecutorRule = InstantTaskExecutorRule()

	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val context: Context = ApplicationProvider.getApplicationContext()
	private val pluginRepository = mockk<PluginRepository>(relaxed = true)
	private val templateCollectionRepository = mockk<TemplateCollectionRepository>(relaxed = true)

	private lateinit var viewModel: ExternalFileInstallViewModel

	@Before
	fun setup() {
		viewModel =
			ExternalFileInstallViewModel(
				pluginRepository = pluginRepository,
				templateCollectionRepository = templateCollectionRepository,
				contentResolver = context.contentResolver,
				filesDir = context.filesDir,
			)
	}

	private fun sourceUriFor(
		fileName: String,
		content: String = "dummy",
	): Uri {
		val file = File(tempFolder.newFolder(), fileName)
		file.writeText(content)
		return Uri.fromFile(file)
	}

	@Test
	fun `unsupported extension shows error and finishes`() =
		runTest {
			viewModel.onReceived(context, sourceUriFor("notes.txt"))

			val first = viewModel.uiEffect.first()
			assertThat(first).isInstanceOf(ExternalFileInstallUiEffect.ShowError::class.java)
		}

	@Test
	fun `cgp when plugin manager unavailable shows setup-incomplete error`() =
		runTest {
			stubPluginManagerAvailable(false)

			viewModel.onReceived(context, sourceUriFor("my-plugin.cgp"))

			val first = viewModel.uiEffect.first()
			assertThat(first).isInstanceOf(ExternalFileInstallUiEffect.ShowError::class.java)
		}

	@Test
	fun `cgt when templates unavailable shows setup-incomplete error`() =
		runTest {
			stubTemplatesFeatureAvailable(false)

			viewModel.onReceived(context, sourceUriFor("my-templates.cgt"))

			val first = viewModel.uiEffect.first()
			assertThat(first).isInstanceOf(ExternalFileInstallUiEffect.ShowError::class.java)
		}

	@Test
	fun `fresh cgp forwards to plugin manager`() =
		runTest {
			stubPluginManagerAvailable(true)

			viewModel.onReceived(context, sourceUriFor("my-plugin.cgp"))

			val first = viewModel.uiEffect.first()
			assertThat(first).isInstanceOf(ExternalFileInstallUiEffect.ForwardToPluginManager::class.java)
		}

	@Test
	fun `fresh cgt with no name collision shows install confirmation`() =
		runTest {
			stubTemplatesFeatureAvailable(true)
			val info = TemplateCollectionRepository.CollectionInfo(templateNames = listOf("Empty Activity"))
			coEvery { templateCollectionRepository.inspectCollection(any()) } returns Result.success(info)
			coEvery { templateCollectionRepository.findExistingCollision(any()) } returns null

			viewModel.onReceived(context, sourceUriFor("my-templates.cgt"))

			val first = viewModel.uiEffect.first()
			assertThat(first).isInstanceOf(ExternalFileInstallUiEffect.ShowTemplateInstallConfirmation::class.java)
			val effect = first as ExternalFileInstallUiEffect.ShowTemplateInstallConfirmation
			assertThat(effect.suggestedBaseName).isEqualTo("my-templates")
			assertThat(effect.info.templateNames).containsExactly("Empty Activity")
		}

	@Test
	fun `cgt with existing name collision shows name conflict`() =
		runTest {
			stubTemplatesFeatureAvailable(true)
			val info = TemplateCollectionRepository.CollectionInfo(templateNames = listOf("Empty Activity"))
			coEvery { templateCollectionRepository.inspectCollection(any()) } returns Result.success(info)
			coEvery { templateCollectionRepository.findExistingCollision(any()) } returns "my-templates"

			viewModel.onReceived(context, sourceUriFor("my-templates.cgt"))

			val first = viewModel.uiEffect.first()
			assertThat(first).isInstanceOf(ExternalFileInstallUiEffect.ShowTemplateNameConflict::class.java)
			assertThat((first as ExternalFileInstallUiEffect.ShowTemplateNameConflict).existingName).isEqualTo("my-templates")
		}

	@Test
	fun `invalid cgt shows invalid-file error`() =
		runTest {
			stubTemplatesFeatureAvailable(true)
			coEvery { templateCollectionRepository.inspectCollection(any()) } returns
				Result.failure(IllegalArgumentException("no templates"))

			viewModel.onReceived(context, sourceUriFor("broken.cgt"))

			val first = viewModel.uiEffect.first()
			assertThat(first).isInstanceOf(ExternalFileInstallUiEffect.ShowError::class.java)
		}

	@Test
	fun `sanitizeBaseName strips filesystem-unsafe characters`() {
		assertThat(viewModel.sanitizeBaseName("my:templates/v2")).isEqualTo("my_templates_v2")
		assertThat(viewModel.sanitizeBaseName("   ")).isEqualTo("templates")
	}

	@Test
	fun `suggestUniqueBaseName bumps suffix until free`() =
		runTest {
			coEvery { templateCollectionRepository.findExistingCollision("foo") } returns "foo"
			coEvery { templateCollectionRepository.findExistingCollision("foo (2)") } returns "foo (2)"
			coEvery { templateCollectionRepository.findExistingCollision("foo (3)") } returns null

			val suggested = viewModel.suggestUniqueBaseName("foo")

			assertThat(suggested).isEqualTo("foo (3)")
		}

	private fun stubPluginManagerAvailable(available: Boolean) {
		every { pluginRepository.isPluginManagerAvailable() } returns available
	}

	private fun stubTemplatesFeatureAvailable(available: Boolean) {
		every { templateCollectionRepository.isTemplatesFeatureAvailable() } returns available
	}
}
