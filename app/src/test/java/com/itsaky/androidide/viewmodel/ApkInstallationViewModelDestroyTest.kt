package com.itsaky.androidide.viewmodel

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.viewmodel.ApkInstallationViewModel.SessionState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ApkInstallationViewModel.destroy] must always release the installer callback that
 * [ApkInstallationViewModel.installApk] registered: the [PackageInstaller] belongs to the process
 * and outlives the view model, so a callback left behind keeps firing into a dead one.
 */
@RunWith(RobolectricTestRunner::class)
class ApkInstallationViewModelDestroyTest {
	private val viewModel = ApkInstallationViewModel()

	private val installer =
		mockk<PackageInstaller> {
			every { unregisterSessionCallback(any()) } just Runs
			every { abandonSession(any()) } just Runs
		}

	private fun session(
		id: Int,
		active: Boolean,
	): PackageInstaller.SessionInfo =
		mockk {
			every { sessionId } returns id
			every { isActive } returns active
		}

	private fun contextWith(sessions: List<PackageInstaller.SessionInfo>): Context {
		every { installer.mySessions } returns sessions
		val packageManager = mockk<PackageManager> { every { packageInstaller } returns installer }
		return mockk { every { this@mockk.packageManager } returns packageManager }
	}

	@Test
	fun `destroy unregisters the callback even when there is no session left to abandon`() {
		// The common case: the install finished, so reloadStatus reports no session. The
		// callback was registered all the same.
		viewModel.destroy(contextWith(emptyList()))

		verify(exactly = 1) { installer.unregisterSessionCallback(any()) }
		verify(exactly = 0) { installer.abandonSession(any()) }
	}

	@Test
	fun `destroy unregisters the callback and abandons a live session`() {
		viewModel.setSessionState(SessionState.InProgress(sessionId = 7, progress = 40))

		viewModel.destroy(contextWith(listOf(session(id = 7, active = true))))

		verify(exactly = 1) { installer.unregisterSessionCallback(any()) }
		verify(exactly = 1) { installer.abandonSession(7) }
		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.Idle)
	}
}
