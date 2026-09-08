package com.itsaky.androidide.viewmodels

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.repositories.DownloadFileConflictException
import com.itsaky.androidide.repositories.TemplateRepository
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import com.itsaky.androidide.ui.models.TemplateManagerUiEffect
import com.itsaky.androidide.ui.models.TemplateManagerUiEvent
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

	/**
	 * Also pins why `_uiEffect` is a `Channel(Channel.BUFFERED)` rather than the rendezvous
	 * default: `onEvent` -> `installTemplate` sends this effect, and only afterward (the
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

			viewModel.onEvent(TemplateManagerUiEvent.InstallTemplate(item))
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

			viewModel.onEvent(TemplateManagerUiEvent.InstallTemplate(item))
			advanceUntilIdle()

			// Only the init{} load - a failed install must not trigger a reload.
			coVerify(exactly = 1) { repository.listTemplateFiles() }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowError).isTrue()
		}

	@Test
	fun uninstallTemplate_onSuccess_reloadsAndSendsShowSuccessEffect() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.uninstallTemplate(item, false) } returns Result.success(Unit)

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.onEvent(TemplateManagerUiEvent.UninstallTemplate(item))
			advanceUntilIdle()

			coVerify(exactly = 1) { repository.uninstallTemplate(item, false) }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowSuccess).isTrue()
		}

	/**
	 * ADFA-5446: a DownloadFileConflictException means Downloads already has a same-named file
	 * (e.g. left behind by the "open a .cgt from outside the app" path) - the ViewModel must ask
	 * before overwriting it, not surface this as a generic error.
	 */
	@Test
	fun uninstallTemplate_onDownloadConflict_showsOverwriteConfirmation_notGenericError() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.uninstallTemplate(item, false) } returns
				Result.failure(DownloadFileConflictException("already exists"))

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.onEvent(TemplateManagerUiEvent.UninstallTemplate(item))
			advanceUntilIdle()

			val effect = viewModel.uiEffect.first()
			assertThat(effect).isInstanceOf(TemplateManagerUiEffect.ShowOverwriteDownloadConfirmation::class.java)
			assertThat((effect as TemplateManagerUiEffect.ShowOverwriteDownloadConfirmation).item).isEqualTo(item)
		}

	/** The dialog's confirm action: retries with overwrite = true and completes normally on success. */
	@Test
	fun confirmUninstallOverwritingDownload_retriesWithOverwriteTrue() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.uninstallTemplate(item, true) } returns Result.success(Unit)

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmUninstallOverwritingDownload(item)
			advanceUntilIdle()

			coVerify(exactly = 1) { repository.uninstallTemplate(item, true) }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowSuccess).isTrue()
		}
}
