package com.itsaky.androidide.viewmodels

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.repositories.TemplateRepository
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import com.itsaky.androidide.ui.models.TemplateManagerUiEffect
import com.itsaky.androidide.ui.models.TemplateManagerUiEvent
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@RunWith(JUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateManagerViewModelTest {
	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	private val repository = mockk<TemplateRepository>()

	private val item =
		CgtFileItem(
			file = File("/tmp/install.cgt"),
			name = "install.cgt",
			templates = listOf(TemplateMetadata("T", "d", "1.0")),
			installed = false,
			provenance = TemplateProvenance.USER,
		)

	@Before
	fun stubAndroidLog() {
		// TemplateManagerViewModel calls android.util.Log.d/Log.e directly; under plain JVM
		// unit tests those throw "not mocked" and the coroutine never reaches its state update.
		mockkStatic(Log::class)
		every { Log.d(any(), any()) } returns 0
		every { Log.e(any(), any(), any()) } returns 0
	}

	@After
	fun cleanup() {
		unmockkStatic(Log::class)
	}

	@Test
	fun init_loadsTemplates_intoUiState() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(listOf(item))

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			assertThat(viewModel.uiState.value.isLoading).isFalse()
			assertThat(viewModel.uiState.value.items).containsExactly(item)
		}

	@Test
	fun installTemplateEvent_onlyAsksForConfirmation() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.onEvent(TemplateManagerUiEvent.InstallTemplate(item))
			advanceUntilIdle()

			assertThat(viewModel.uiEffect.first()).isEqualTo(TemplateManagerUiEffect.ShowInstallConfirmation(item))
			coVerify(exactly = 0) { repository.installTemplate(any()) }
		}

	@Test
	fun confirmInstallTemplate_withoutDelete_keepsTheDownloadsOriginal() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.installTemplate(item) } returns Result.success(Unit)

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmInstallTemplate(item, deleteSourceAfterInstall = false)
			advanceUntilIdle()

			coVerify(exactly = 0) { repository.deleteDownloadFile(any()) }
		}

	@Test
	fun confirmInstallTemplate_withDelete_deletesTheDownloadsOriginalAfterInstalling() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.installTemplate(item) } returns Result.success(Unit)
			coEvery { repository.deleteDownloadFile(item) } returns Result.success(Unit)

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmInstallTemplate(item, deleteSourceAfterInstall = true)
			advanceUntilIdle()

			coVerifyOrder {
				repository.installTemplate(item)
				repository.deleteDownloadFile(item)
			}
		}

	@Test
	fun confirmInstallTemplate_deleteFails_stillReportsTheInstall_thenTheDeleteError() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.installTemplate(item) } returns Result.success(Unit)
			coEvery { repository.deleteDownloadFile(item) } returns Result.failure(java.io.IOException("ro"))

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmInstallTemplate(item, deleteSourceAfterInstall = true)
			advanceUntilIdle()

			val effects = viewModel.uiEffect.take(2).toList()
			assertThat(effects[0]).isInstanceOf(TemplateManagerUiEffect.ShowSuccess::class.java)
			assertThat(effects[1]).isEqualTo(TemplateManagerUiEffect.ShowError(R.string.msg_source_delete_failed))
		}

	@Test
	fun confirmInstallTemplate_installFails_neverDeletesTheDownloadsOriginal() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.installTemplate(item) } returns Result.failure(java.io.IOException("boom"))

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmInstallTemplate(item, deleteSourceAfterInstall = true)
			advanceUntilIdle()

			coVerify(exactly = 0) { repository.deleteDownloadFile(any()) }
		}

	/**
	 * Also pins why `_uiEffect` is a `Channel(Channel.BUFFERED)` rather than the rendezvous
	 * default: `confirmInstallTemplate` sends this effect, and only afterward (the
	 * `advanceUntilIdle()` below) does anything collect `uiEffect` via `.first()` - mirroring
	 * production, where the screen's `LaunchedEffect` collector attaches on first composition,
	 * strictly after the ViewModel (and its `init { loadTemplates() }`) is constructed. A
	 * rendezvous channel would drop this send with nothing collecting yet; `first()` returning
	 * it here proves it was buffered instead.
	 */
	@Test
	fun installTemplate_onSuccess_reloadsAndSendsShowSuccessEffect() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.installTemplate(item) } returns Result.success(Unit)

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmInstallTemplate(item, deleteSourceAfterInstall = false)
			advanceUntilIdle()

			coVerify(exactly = 1) { repository.installTemplate(item) }
			// listTemplateFiles is called once by init{} and again by the post-install reload.
			coVerify(exactly = 2) { repository.listTemplateFiles() }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowSuccess).isTrue()
		}

	@Test
	fun installTemplate_onFailure_sendsShowErrorEffect_withoutReloading() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.installTemplate(item) } returns Result.failure(java.io.IOException("boom"))

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmInstallTemplate(item, deleteSourceAfterInstall = false)
			advanceUntilIdle()

			// Only the init{} load - a failed install must not trigger a reload.
			coVerify(exactly = 1) { repository.listTemplateFiles() }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowError).isTrue()
		}
}
