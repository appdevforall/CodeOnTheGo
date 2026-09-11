package com.itsaky.androidide.viewmodel

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.viewmodel.ApkInstallationViewModel.SessionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The four ways [ApkInstallationViewModel.reloadStatus] recovers the install-progress UI.
 *
 * The state outlives the installer session that produced it: the session can be gone, or finished,
 * or have been abandoned by the system, while the view model still holds an [SessionState.InProgress]
 * from before the activity was recreated. Every one of those must land back on
 * [SessionState.Idle] - a stale `sessionId` that does not is an install indicator the user cannot
 * get rid of, since nothing else ever clears it. The other direction matters too: a session that
 * IS live must keep its state, or a running install loses its progress bar.
 */
@RunWith(RobolectricTestRunner::class)
class ApkInstallationViewModelReloadStatusTest {
	private val viewModel = ApkInstallationViewModel()

	private fun session(
		id: Int,
		active: Boolean,
	): PackageInstaller.SessionInfo =
		mockk {
			every { sessionId } returns id
			every { isActive } returns active
		}

	private val installer = mockk<PackageInstaller>()

	private fun contextWith(sessions: List<PackageInstaller.SessionInfo>): Context {
		every { installer.mySessions } returns sessions
		val packageManager = mockk<PackageManager> { every { packageInstaller } returns installer }
		return mockk { every { this@mockk.packageManager } returns packageManager }
	}

	@Test
	fun `an idle view model reports no session with the -1 sentinel`() {
		// Callers treat any other value as a session they may abandon, so the sentinel is the
		// contract, not an implementation detail.
		val sessionId = viewModel.reloadStatus(contextWith(emptyList()))

		assertThat(sessionId).isEqualTo(-1)
		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.Idle)
	}

	@Test
	fun `a state holding the sentinel session id falls back to idle without an installer lookup`() {
		viewModel.setSessionState(SessionState.InProgress(sessionId = -1, progress = 40))

		val sessionId = viewModel.reloadStatus(contextWith(emptyList()))

		assertThat(sessionId).isEqualTo(-1)
		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.Idle)
		// -1 is not a session that can exist, so asking the installer about it is how an invalid
		// id gets treated as a real one.
		verify(exactly = 0) { installer.mySessions }
	}

	@Test
	fun `a session the installer no longer knows falls back to idle`() {
		// The defect this pins: the install indicator stays up forever on a sessionId that
		// outlived its session.
		viewModel.setSessionState(SessionState.InProgress(sessionId = 7, progress = 40))

		val sessionId = viewModel.reloadStatus(contextWith(listOf(session(id = 9, active = true))))

		assertThat(sessionId).isEqualTo(-1)
		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.Idle)
	}

	@Test
	fun `a session that is no longer active falls back to idle`() {
		viewModel.setSessionState(SessionState.InProgress(sessionId = 7, progress = 40))

		val sessionId = viewModel.reloadStatus(contextWith(listOf(session(id = 7, active = false))))

		assertThat(sessionId).isEqualTo(-1)
		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.Idle)
	}

	@Test
	fun `a finished state whose session is gone falls back to idle`() {
		// Finished carries a sessionId too, and reaches reloadStatus whenever the activity is
		// recreated before the result is handled.
		viewModel.setSessionState(SessionState.Finished(sessionId = 7, isSuccess = true))

		val sessionId = viewModel.reloadStatus(contextWith(emptyList()))

		assertThat(sessionId).isEqualTo(-1)
		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.Idle)
	}

	@Test
	fun `a live session keeps its in-progress state and returns its id`() {
		val inProgress = SessionState.InProgress(sessionId = 7, progress = 40)
		viewModel.setSessionState(inProgress)

		val sessionId = viewModel.reloadStatus(contextWith(listOf(session(id = 7, active = true))))

		assertThat(sessionId).isEqualTo(7)
		assertThat(viewModel.sessionState.value).isEqualTo(inProgress)
	}
}
