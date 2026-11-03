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
import io.mockk.slot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ApkInstallationViewModelSessionFilterTest {
	private val viewModel = ApkInstallationViewModel()

	private val callback = slot<PackageInstaller.SessionCallback>()

	private val ownSession: PackageInstaller.SessionInfo = mockk { every { sessionId } returns OWN_SESSION }

	private val installer =
		mockk<PackageInstaller> {
			every { unregisterSessionCallback(any()) } just Runs
			every { registerSessionCallback(capture(callback)) } just Runs
			every { mySessions } returns listOf(ownSession)
		}

	private val context: Context =
		mockk {
			val packageManager = mockk<PackageManager> { every { packageInstaller } returns installer }
			every { applicationContext } returns this
			every { this@mockk.packageManager } returns packageManager
		}

	private fun startInstall(): PackageInstaller.SessionCallback {
		viewModel.installApk(context, File("absent.apk"), launchInDebugMode = false)
		return callback.captured
	}

	@Test
	fun `another app's failed session does not report this install as failed`() {
		val callback = startInstall()
		callback.onCreated(OWN_SESSION)

		callback.onFinished(FOREIGN_SESSION, false)

		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.InProgress(OWN_SESSION, 0))
	}

	@Test
	fun `another app's session does not take over the install progress`() {
		val callback = startInstall()
		callback.onCreated(OWN_SESSION)

		callback.onCreated(FOREIGN_SESSION)
		callback.onProgressChanged(FOREIGN_SESSION, 0.5f)

		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.InProgress(OWN_SESSION, 0))
	}

	@Test
	fun `this install's own result is still reported`() {
		val callback = startInstall()
		callback.onCreated(OWN_SESSION)
		callback.onProgressChanged(OWN_SESSION, 0.5f)
		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.InProgress(OWN_SESSION, 50))

		callback.onFinished(OWN_SESSION, true)

		assertThat(viewModel.sessionState.value).isEqualTo(SessionState.Finished(OWN_SESSION, true))
	}

	private companion object {
		const val OWN_SESSION = 5
		const val FOREIGN_SESSION = 9
	}
}
