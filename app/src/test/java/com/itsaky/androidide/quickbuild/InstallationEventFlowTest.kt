package com.itsaky.androidide.quickbuild

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.events.InstallationEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.service.provision.InstallBroadcast
import org.junit.Test

/**
 * Pins the PackageInstaller status -> [InstallBroadcast.Status] mapping. The boundaries
 * matter: STATUS_FAILURE_ABORTED numerically satisfies `code >= STATUS_FAILURE`, so a branch
 * reorder would silently turn "the user declined" (retryable) into "the install is broken".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallationEventFlowTest {
	private fun resultEvent(
		status: Int?,
		packageName: String? = "com.example.app",
		message: String? = null,
	): InstallationEvent.InstallationResultEvent {
		val extras = mockk<Bundle>()
		every { extras.getInt(PackageInstaller.EXTRA_STATUS, any()) } answers {
			status ?: secondArg()
		}
		every { extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME) } returns packageName
		every { extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE) } returns message
		val intent = mockk<Intent>()
		every { intent.extras } returns extras
		return InstallationEvent.InstallationResultEvent(intent)
	}

	private fun broadcastsFor(vararg events: InstallationEvent.InstallationResultEvent): List<InstallBroadcast> {
		val received = mutableListOf<InstallBroadcast>()
		runTest {
			val flow = InstallationEventFlow()
			val collector =
				launch(UnconfinedTestDispatcher(testScheduler)) {
					flow.broadcasts.collect { received += it }
				}
			events.forEach(flow::onInstallationResult)
			collector.cancel()
		}
		return received
	}

	@Test
	fun `success maps to SUCCESS with the package name and message passed through`() {
		val broadcasts =
			broadcastsFor(
				resultEvent(
					PackageInstaller.STATUS_SUCCESS,
					packageName = "com.example.installed",
					message = "ok",
				),
			)

		assertThat(broadcasts).hasSize(1)
		assertThat(broadcasts[0].status).isEqualTo(InstallBroadcast.Status.SUCCESS)
		assertThat(broadcasts[0].packageName).isEqualTo("com.example.installed")
		assertThat(broadcasts[0].message).isEqualTo("ok")
	}

	@Test
	fun `pending user action maps to PENDING_USER_ACTION`() {
		val broadcasts = broadcastsFor(resultEvent(PackageInstaller.STATUS_PENDING_USER_ACTION))

		assertThat(broadcasts.single().status)
			.isEqualTo(InstallBroadcast.Status.PENDING_USER_ACTION)
	}

	@Test
	fun `a user-declined install maps to ABORTED, not FAILURE`() {
		// STATUS_FAILURE_ABORTED >= STATUS_FAILURE, so this only passes while the ABORTED
		// branch stays ahead of the generic failure catch-all.
		val broadcasts = broadcastsFor(resultEvent(PackageInstaller.STATUS_FAILURE_ABORTED))

		assertThat(broadcasts.single().status).isEqualTo(InstallBroadcast.Status.ABORTED)
	}

	@Test
	fun `every other failure code at or above STATUS_FAILURE maps to FAILURE`() {
		val broadcasts =
			broadcastsFor(
				resultEvent(PackageInstaller.STATUS_FAILURE),
				resultEvent(PackageInstaller.STATUS_FAILURE_BLOCKED),
				resultEvent(PackageInstaller.STATUS_FAILURE_STORAGE),
			)

		assertThat(broadcasts).hasSize(3)
		broadcasts.forEach {
			assertThat(it.status).isEqualTo(InstallBroadcast.Status.FAILURE)
		}
	}

	@Test
	fun `an intent without a status extra maps to OTHER`() {
		val broadcasts = broadcastsFor(resultEvent(status = null))

		assertThat(broadcasts.single().status).isEqualTo(InstallBroadcast.Status.OTHER)
	}

	@Test
	fun `an intent with no extras emits nothing`() {
		val intent = mockk<Intent>()
		every { intent.extras } returns null

		val broadcasts = broadcastsFor(InstallationEvent.InstallationResultEvent(intent))

		assertThat(broadcasts).isEmpty()
	}
}
