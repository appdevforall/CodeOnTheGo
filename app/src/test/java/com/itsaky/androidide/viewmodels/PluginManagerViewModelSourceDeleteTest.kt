package com.itsaky.androidide.viewmodels

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.ui.models.PluginInstallSource
import com.itsaky.androidide.ui.models.PluginManagerUiEffect
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.viewmodel.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * "Delete after install" for a plugin picked from the Manager screen's Downloads list, which
 * arrives as a file:// Uri rather than SAF's content:// (ADFA-6729).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginManagerViewModelSourceDeleteTest {
	@get:Rule
	val instantExecutorRule = InstantTaskExecutorRule()

	@get:Rule
	val mainDispatcherRule = MainDispatcherRule()

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val context: Context = ApplicationProvider.getApplicationContext()

	private val pluginRepository =
		mockk<PluginRepository>(relaxed = true) {
			every { isPluginManagerAvailable() } returns true
			coEvery { getAllPlugins() } returns Result.success(emptyList())
			coEvery { installPluginFromFile(any()) } returns Result.success(Unit)
		}

	private fun newViewModel() =
		PluginManagerViewModel(
			pluginRepository = pluginRepository,
			contentResolver = context.contentResolver,
			filesDir = tempFolder.newFolder("files"),
		)

	private fun downloadedPlugin(): File = File(tempFolder.newFolder("Download"), "my-plugin.cgp").apply { writeText("cgp") }

	@Test
	fun `file uri source is deleted after a successful install when asked`() =
		runTest {
			val source = downloadedPlugin()
			val viewModel = newViewModel()

			viewModel.onEvent(
				PluginManagerUiEvent.ConfirmOverwrite(
					PluginInstallSource.ContentUri(Uri.fromFile(source)),
					deleteSourceAfterInstall = true,
				),
			)

			// The install hops to Dispatchers.IO, so wait in real time, not virtual time.
			withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { while (source.exists()) delay(20) } }
			assertThat(source.exists()).isFalse()
		}

	@Test
	fun `file uri source is kept when not asked to delete`() =
		runTest {
			val source = downloadedPlugin()
			val viewModel = newViewModel()

			viewModel.onEvent(
				PluginManagerUiEvent.ConfirmOverwrite(
					PluginInstallSource.ContentUri(Uri.fromFile(source)),
					deleteSourceAfterInstall = false,
				),
			)

			// Wait for the install to finish, then check the source is untouched.
			withContext(Dispatchers.Default) {
				withTimeoutOrNull(5_000) { viewModel.uiEffect.first { it is PluginManagerUiEffect.ShowRestartPrompt } }
			}
			assertThat(source.exists()).isTrue()
		}
}
