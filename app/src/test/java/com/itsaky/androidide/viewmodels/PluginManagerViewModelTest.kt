package com.itsaky.androidide.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.plugins.PluginMetadata
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.ui.models.PluginManagerUiEffect
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.utils.ConnectivityObserver
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A fake [ConnectivityObserver] whose emissions are driven by [state], for tests that need to simulate a change over time. */
private class FakeConnectivityObserver(
	initialValue: Boolean,
) : ConnectivityObserver {
	val state = MutableStateFlow(initialValue)

	override fun observe() = state
}

/**
 * Covers only [PluginManagerViewModel.uiState]'s connectivity and offline-mode fields (ADFA-5646).
 * Install-source deletion is covered by [PluginManagerViewModelSourceDeleteTest].
 */
@RunWith(JUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginManagerViewModelTest {
	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	private val pluginRepository =
		mockk<PluginRepository> {
			// Keeps init{}'s loadPlugins() a no-op past this check, so the test doesn't need to
			// stub the rest of the load path - irrelevant to what's under test here.
			every { isPluginManagerAvailable() } returns false
		}

	private fun viewModel(
		connectivityObserver: ConnectivityObserver,
		offlineMode: Boolean = false,
	) = PluginManagerViewModel(
		pluginRepository = pluginRepository,
		contentResolver = mockk(relaxed = true),
		filesDir = File("/tmp"),
		connectivityObserver = connectivityObserver,
		isOfflineModeEnabled = { offlineMode },
	)

	@Test
	fun uiState_isOnline_reflectsConnectivityObserverOnInit() =
		runTest {
			val viewModel = viewModel(FakeConnectivityObserver(initialValue = false))
			advanceUntilIdle()

			assertThat(viewModel.uiState.value.isOnline).isFalse()
		}

	@Test
	fun uiState_isOnline_updatesWhenConnectivityChanges() =
		runTest {
			val connectivityObserver = FakeConnectivityObserver(initialValue = true)
			val viewModel = viewModel(connectivityObserver)
			advanceUntilIdle()
			assertThat(viewModel.uiState.value.isOnline).isTrue()

			connectivityObserver.state.value = false
			advanceUntilIdle()

			assertThat(viewModel.uiState.value.isOnline).isFalse()
		}

	@Test
	fun showDiscoverPlugins_whenOnlineAndNotInOfflineMode() =
		runTest {
			val viewModel = viewModel(FakeConnectivityObserver(initialValue = true), offlineMode = false)
			advanceUntilIdle()

			assertThat(viewModel.uiState.value.showDiscoverPlugins).isTrue()
		}

	@Test
	fun showDiscoverPlugins_hiddenInOfflineModeEvenWhenOnline() =
		runTest {
			val viewModel = viewModel(FakeConnectivityObserver(initialValue = true), offlineMode = true)
			advanceUntilIdle()

			assertThat(viewModel.uiState.value.showDiscoverPlugins).isFalse()
		}

	@Test
	fun showDiscoverPlugins_hiddenWhenOffline() =
		runTest {
			val viewModel = viewModel(FakeConnectivityObserver(initialValue = false), offlineMode = false)
			advanceUntilIdle()

			assertThat(viewModel.uiState.value.showDiscoverPlugins).isFalse()
		}
}

/**
 * Warnings before removing or disabling a plugin that came with the app (ADFA-7240). Robolectric
 * because loading the plugin list refreshes [com.itsaky.androidide.utils.EditorDecorationBridge].
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginManagerViewModelBundledTest {
	@get:Rule
	val instantExecutorRule = InstantTaskExecutorRule()

	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	private fun plugin(id: String) =
		PluginInfo(
			metadata = PluginMetadata(id, id, "1.0", "d", "a", "1.0"),
			isEnabled = true,
			isLoaded = true,
		)

	private val bundledPlugin = plugin("bundled.plugin")
	private val userPlugin = plugin("user.plugin")

	private val pluginRepository =
		mockk<PluginRepository>(relaxed = true) {
			every { isPluginManagerAvailable() } returns true
			coEvery { getAllPlugins() } returns Result.success(listOf(bundledPlugin, userPlugin))
			coEvery { disablePlugin(any()) } returns Result.success(true)
		}

	private fun TestScope.viewModel(isBundledPlugin: ((String) -> Boolean)? = null): PluginManagerViewModel {
		val connectivity = FakeConnectivityObserver(initialValue = true)
		val viewModel =
			if (isBundledPlugin == null) {
				PluginManagerViewModel(pluginRepository, mockk(relaxed = true), File("/tmp"), connectivity, { false })
			} else {
				PluginManagerViewModel(
					pluginRepository,
					mockk(relaxed = true),
					File("/tmp"),
					connectivity,
					{ false },
					isBundledPlugin,
				)
			}
		advanceUntilIdle()
		return viewModel
	}

	private suspend fun PluginManagerViewModel.firstEffect() = withTimeout(5_000) { uiEffect.first() }

	@Test
	fun uninstall_bundledPlugin_sendsBundledTrue() =
		runTest {
			val viewModel = viewModel { it == "bundled.plugin" }

			viewModel.onEvent(PluginManagerUiEvent.UninstallPlugin("bundled.plugin"))

			assertThat(viewModel.firstEffect())
				.isEqualTo(PluginManagerUiEffect.ShowUninstallConfirmation(bundledPlugin, bundled = true))
		}

	@Test
	fun uninstall_userPlugin_sendsBundledFalse() =
		runTest {
			val viewModel = viewModel { it == "bundled.plugin" }

			viewModel.onEvent(PluginManagerUiEvent.UninstallPlugin("user.plugin"))

			assertThat(viewModel.firstEffect())
				.isEqualTo(PluginManagerUiEffect.ShowUninstallConfirmation(userPlugin, bundled = false))
		}

	@Test
	fun uninstall_withoutABundledLambda_isNotBundled() =
		runTest {
			val viewModel = viewModel()

			viewModel.onEvent(PluginManagerUiEvent.UninstallPlugin("bundled.plugin"))

			assertThat(viewModel.firstEffect())
				.isEqualTo(PluginManagerUiEffect.ShowUninstallConfirmation(bundledPlugin, bundled = false))
		}

	@Test
	fun disable_bundledPlugin_asksFirstAndDoesNotDisable() =
		runTest {
			val viewModel = viewModel { it == "bundled.plugin" }

			viewModel.onEvent(PluginManagerUiEvent.DisablePlugin("bundled.plugin"))

			assertThat(viewModel.firstEffect()).isEqualTo(PluginManagerUiEffect.ShowDisableConfirmation(bundledPlugin))
			coVerify(exactly = 0) { pluginRepository.disablePlugin(any()) }
		}

	@Test
	fun disable_userPlugin_disablesAtOnce() =
		runTest {
			val viewModel = viewModel { it == "bundled.plugin" }

			viewModel.onEvent(PluginManagerUiEvent.DisablePlugin("user.plugin"))

			assertThat(viewModel.firstEffect()).isInstanceOf(PluginManagerUiEffect.ShowSuccess::class.java)
			coVerify(exactly = 1) { pluginRepository.disablePlugin("user.plugin") }
		}

	@Test
	fun disable_withoutABundledLambda_disablesAtOnce() =
		runTest {
			val viewModel = viewModel()

			viewModel.onEvent(PluginManagerUiEvent.DisablePlugin("bundled.plugin"))

			assertThat(viewModel.firstEffect()).isInstanceOf(PluginManagerUiEffect.ShowSuccess::class.java)
			coVerify(exactly = 1) { pluginRepository.disablePlugin("bundled.plugin") }
		}

	@Test
	fun confirmDisablePlugin_disables() =
		runTest {
			val viewModel = viewModel { true }

			viewModel.confirmDisablePlugin("bundled.plugin")

			assertThat(viewModel.firstEffect()).isInstanceOf(PluginManagerUiEffect.ShowSuccess::class.java)
			coVerify(exactly = 1) { pluginRepository.disablePlugin("bundled.plugin") }
		}
}
