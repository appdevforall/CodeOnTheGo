package com.itsaky.androidide.viewmodels

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.repositories.DownloadFilesRepository
import com.itsaky.androidide.repositories.DownloadedFile
import com.itsaky.androidide.ui.models.AddonFileKind
import com.itsaky.androidide.ui.models.DownloadPickerUiState
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPickerViewModelTest {
	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	private val plugin = DownloadedFile(File("/dl/a.cgp"), 10, 1)
	private val template = DownloadedFile(File("/dl/b.cgt"), 20, 2)

	private val repository =
		mockk<DownloadFilesRepository> {
			coEvery { list("cgp") } returns listOf(plugin)
			coEvery { list("cgt") } returns listOf(template)
		}

	@Test
	fun `starts hidden`() {
		assertThat(DownloadPickerViewModel(repository).uiState.value).isEqualTo(DownloadPickerUiState.Hidden)
	}

	@Test
	fun `plugin tab lists cgp files and template tab lists cgt files`() =
		runTest {
			val viewModel = DownloadPickerViewModel(repository)

			viewModel.open(AddonFileKind.PLUGIN)
			assertThat(viewModel.uiState.value)
				.isEqualTo(DownloadPickerUiState.Open(AddonFileKind.PLUGIN, listOf(plugin)))

			viewModel.open(AddonFileKind.TEMPLATE)
			assertThat(viewModel.uiState.value)
				.isEqualTo(DownloadPickerUiState.Open(AddonFileKind.TEMPLATE, listOf(template)))
		}

	@Test
	fun `shows loading until the folder has been read`() =
		runTest {
			val pending = CompletableDeferred<List<DownloadedFile>>()
			coEvery { repository.list("cgp") } coAnswers { pending.await() }
			val viewModel = DownloadPickerViewModel(repository)

			viewModel.open(AddonFileKind.PLUGIN)
			assertThat(viewModel.uiState.value).isEqualTo(DownloadPickerUiState.Open(AddonFileKind.PLUGIN, files = null))

			pending.complete(listOf(plugin))
			assertThat(viewModel.uiState.value)
				.isEqualTo(DownloadPickerUiState.Open(AddonFileKind.PLUGIN, listOf(plugin)))
		}

	@Test
	fun `a listing that finishes after dismiss does not reopen the dialog`() =
		runTest {
			val pending = CompletableDeferred<List<DownloadedFile>>()
			coEvery { repository.list("cgp") } coAnswers { pending.await() }
			val viewModel = DownloadPickerViewModel(repository)

			viewModel.open(AddonFileKind.PLUGIN)
			viewModel.dismiss()
			pending.complete(listOf(plugin))

			assertThat(viewModel.uiState.value).isEqualTo(DownloadPickerUiState.Hidden)
		}

	@Test
	fun `a stale listing for the other tab is not shown`() =
		runTest {
			val slowPlugins = CompletableDeferred<List<DownloadedFile>>()
			coEvery { repository.list("cgp") } coAnswers { slowPlugins.await() }
			val viewModel = DownloadPickerViewModel(repository)

			viewModel.open(AddonFileKind.PLUGIN)
			viewModel.open(AddonFileKind.TEMPLATE)
			slowPlugins.complete(listOf(plugin))

			assertThat(viewModel.uiState.value)
				.isEqualTo(DownloadPickerUiState.Open(AddonFileKind.TEMPLATE, listOf(template)))
		}
}
