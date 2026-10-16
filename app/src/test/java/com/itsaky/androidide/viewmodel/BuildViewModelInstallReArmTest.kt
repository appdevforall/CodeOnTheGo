package com.itsaky.androidide.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Pins the rotation-safety contract of the install hand-off (ADFA-4128): the activity resets
 * [BuildState.AwaitingInstall] to Idle as soon as it takes the install, then re-arms it if the
 * dispatch was dropped (activity destroyed mid-parse) so the recreated activity retries
 * instead of silently losing a successful build's install.
 */
class BuildViewModelInstallReArmTest {
	private val awaiting =
		BuildState.AwaitingInstall(
			apkFile = File("app-debug.apk"),
			launchInDebugMode = false,
		)

	@Test
	fun `a dropped dispatch re-arms AwaitingInstall from Idle`() {
		val viewModel = BuildViewModel()

		viewModel.reArmInstall(awaiting)

		assertThat(viewModel.buildState.value).isEqualTo(awaiting)
	}

	@Test
	fun `re-arm does not overwrite a state that is no longer Idle`() {
		val viewModel = BuildViewModel()
		viewModel.reArmInstall(awaiting)

		val other =
			BuildState.AwaitingInstall(
				apkFile = File("other.apk"),
				launchInDebugMode = true,
			)
		viewModel.reArmInstall(other)

		assertThat(viewModel.buildState.value).isEqualTo(awaiting)
	}

	@Test
	fun `installationAttempted still resets a re-armed install to Idle`() {
		val viewModel = BuildViewModel()
		viewModel.reArmInstall(awaiting)

		viewModel.installationAttempted()

		assertThat(viewModel.buildState.value).isEqualTo(BuildState.Idle)
	}
}
