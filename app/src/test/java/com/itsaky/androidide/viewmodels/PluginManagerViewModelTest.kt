package com.itsaky.androidide.viewmodels

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.utils.ConnectivityObserver
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/** A fake [ConnectivityObserver] whose emissions are driven by [state], for tests that need to simulate a change over time. */
private class FakeConnectivityObserver(
	initialValue: Boolean,
) : ConnectivityObserver {
	val state = MutableStateFlow(initialValue)

	override fun observe() = state
}

/**
 * Covers only [PluginManagerViewModel.uiState]'s `isOnline` field (ADFA-5646) - the rest of this
 * ViewModel has no test coverage yet, which is out of scope here.
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

	private fun viewModel(connectivityObserver: ConnectivityObserver) =
		PluginManagerViewModel(
			pluginRepository = pluginRepository,
			contentResolver = mockk(relaxed = true),
			filesDir = File("/tmp"),
			connectivityObserver = connectivityObserver,
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
}
