package com.itsaky.androidide.viewmodel

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.assets.AssetsInstallationHelper
import com.itsaky.androidide.assets.AssetsInstallerProgressConsumer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * ADFA-977: after a failed install (e.g. missing assets zip), pressing "Finish installation"
 * again must leave the stale InstallationError at once. PermissionsFragment waits for the next
 * Complete/Error with state.first {}, so a lingering error ends that wait immediately: no
 * progress shown, and no navigation when the retry succeeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallationViewModelRetryTest {
	// Standard, not Unconfined: queued work runs only when advanced, so the assertion sees the
	// state startIdeSetup() leaves behind on return. The same dispatcher stands in for IO, so the
	// whole first attempt drains in one advanceUntilIdle().
	@get:Rule
	val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

	private val ctx: Context = mockk(relaxed = true)

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `retry after a failed install replaces the stale error before returning`() =
		run {
			val viewModel = spyk(InstallationViewModel(ioDispatcher = mainDispatcherRule.testDispatcher))
			coEvery { viewModel.checkStorageAndNotify(any()) } returns true
			every { viewModel["checkToolsIsInstalled"]() } returns false

			mockkObject(AssetsInstallationHelper)
			coEvery {
				AssetsInstallationHelper.install(any(), any<AssetsInstallerProgressConsumer>())
			} returns AssetsInstallationHelper.Result.Failure(null, "missing", shouldReportToGlitchTip = false)

			viewModel.startIdeSetup(ctx)
			mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
			assertThat(viewModel.state.value).isInstanceOf(InstallationState.InstallationError::class.java)

			// Not advanced again: the retry's coroutine stays queued, isolating what startIdeSetup()
			// itself set before returning.
			viewModel.startIdeSetup(ctx)

			assertThat(viewModel.state.value).isInstanceOf(InstallationState.Installing::class.java)
		}
}
