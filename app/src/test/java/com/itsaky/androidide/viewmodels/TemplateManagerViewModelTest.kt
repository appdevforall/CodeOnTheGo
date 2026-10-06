package com.itsaky.androidide.viewmodels

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.repositories.TemplateReplaceConflictException
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

	private val installedItem =
		CgtFileItem(
			file = File("/tmp/uninstall.cgt"),
			name = "uninstall.cgt",
			templates = listOf(TemplateMetadata("T", "d", "1.0")),
			installed = true,
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
	fun uninstallTemplate_event_asksForConfirmation_withoutCallingRepository() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.onEvent(TemplateManagerUiEvent.UninstallTemplate(installedItem))
			advanceUntilIdle()

			coVerify(exactly = 0) { repository.uninstallTemplate(any(), any()) }
			val effect = viewModel.uiEffect.first()
			assertThat(effect).isInstanceOf(TemplateManagerUiEffect.ShowUninstallConfirmation::class.java)
			assertThat((effect as TemplateManagerUiEffect.ShowUninstallConfirmation).item).isEqualTo(installedItem)
		}

	@Test
	fun confirmUninstallTemplate_onSuccess_reloadsAndSendsShowSuccessEffect() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.uninstallTemplate(installedItem, false) } returns Result.success(Unit)

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmUninstallTemplate(installedItem)
			advanceUntilIdle()

			coVerify(exactly = 2) { repository.listTemplateFiles() }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowSuccess).isTrue()
		}

	@Test
	fun confirmUninstallTemplate_onReplaceConflict_sendsShowReplaceConfirmation_notShowError() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.uninstallTemplate(installedItem, false) } returns
				Result.failure(TemplateReplaceConflictException(installedItem.name))

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmUninstallTemplate(installedItem)
			advanceUntilIdle()

			// A conflict isn't a reload-worthy outcome and must not be reported as a generic error.
			coVerify(exactly = 1) { repository.listTemplateFiles() }
			val effect = viewModel.uiEffect.first()
			assertThat(effect).isInstanceOf(TemplateManagerUiEffect.ShowReplaceConfirmation::class.java)
			assertThat((effect as TemplateManagerUiEffect.ShowReplaceConfirmation).item).isEqualTo(installedItem)
		}

	@Test
	fun confirmUninstallTemplate_withOverwrite_passesOverwriteThrough_onReplaceConfirm() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.uninstallTemplate(installedItem, true) } returns Result.success(Unit)

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmUninstallTemplate(installedItem, overwrite = true)
			advanceUntilIdle()

			coVerify(exactly = 1) { repository.uninstallTemplate(installedItem, true) }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowSuccess).isTrue()
		}

	@Test
	fun confirmUninstallTemplate_onOtherFailure_sendsShowErrorEffect_withoutReloading() =
		runTest {
			coEvery { repository.listTemplateFiles() } returns Result.success(emptyList())
			coEvery { repository.uninstallTemplate(installedItem, false) } returns Result.failure(java.io.IOException("boom"))

			val viewModel = TemplateManagerViewModel(repository)
			advanceUntilIdle()

			viewModel.confirmUninstallTemplate(installedItem)
			advanceUntilIdle()

			coVerify(exactly = 1) { repository.listTemplateFiles() }
			assertThat(viewModel.uiEffect.first() is TemplateManagerUiEffect.ShowError).isTrue()
		}
}
