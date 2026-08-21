package org.appdevforall.cotg.quickbuild.domain.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * A failed session START must keep the error tone on the bolt until the user's next tap or save
 * (Q8): `Idle -> Provisioning -> Idle on ProvisioningFailed` used to land in a plain Idle whose
 * bolt reads READY, so the user saw a green bolt right after the failure flash.
 */
class FailedStartToneTest {
	private val reducer = SessionReducer()

	private fun failedStartIdle(): QuickBuildSessionState {
		val provisioning =
			reducer.reduce(QuickBuildSessionState.Idle(), SessionEvent.QuickBuildTapped()).state
		assertThat(provisioning).isInstanceOf(QuickBuildSessionState.Provisioning::class.java)
		return reducer
			.reduce(provisioning, SessionEvent.ProvisioningFailed(QuickBuildMessage.Literal("boom")))
			.state
	}

	@Test
	fun `a failed start reads as ERROR, not READY`() {
		val state = failedStartIdle()

		assertThat(QuickBuildStatus.from(state).toTone()).isEqualTo(QuickBuildTone.ERROR)
	}

	@Test
	fun `a tap after a failed start provisions again with the ordinary BUILDING tone`() {
		val transition = reducer.reduce(failedStartIdle(), SessionEvent.QuickBuildTapped())

		assertThat(transition.state)
			.isEqualTo(QuickBuildSessionState.Provisioning(userInitiated = true))
		assertThat(transition.effects).containsExactly(SessionEffect.StartProvisioning)
		assertThat(QuickBuildStatus.from(transition.state).toTone())
			.isEqualTo(QuickBuildTone.BUILDING)
	}

	@Test
	fun `a successful start after a failed one shows ordinary tones throughout`() {
		val provisioning = reducer.reduce(failedStartIdle(), SessionEvent.QuickBuildTapped()).state
		val ready = reducer.reduce(provisioning, SessionEvent.ProvisioningSucceeded(1)).state

		assertThat(ready).isEqualTo(QuickBuildSessionState.Ready(1))
		assertThat(QuickBuildStatus.from(ready).toTone()).isEqualTo(QuickBuildTone.READY)
	}

	@Test
	fun `the failed start lands in Idle with the flag and the status carries it`() {
		val state = failedStartIdle()

		assertThat(state).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
		assertThat(QuickBuildStatus.from(state))
			.isEqualTo(QuickBuildStatus.Hidden(lastStartFailed = true))
	}

	@Test
	fun `a save clears the tone and does NOT retry the start`() {
		val transition = reducer.reduce(failedStartIdle(), SessionEvent.FileSaved)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		// No effect at all: the save is the clearing gesture, never a provision.
		assertThat(transition.effects).isEmpty()
		assertThat(QuickBuildStatus.from(transition.state).toTone()).isEqualTo(QuickBuildTone.READY)
	}

	@Test
	fun `a save on a plain Idle is a no-op`() {
		val transition = reducer.reduce(QuickBuildSessionState.Idle(), SessionEvent.FileSaved)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a save on a live session is a no-op - the watcher owns live saves`() {
		val ready = QuickBuildSessionState.Ready(3)

		val transition = reducer.reduce(ready, SessionEvent.FileSaved)

		assertThat(transition.state).isEqualTo(ready)
		assertThat(transition.effects).isEmpty()
	}

	@Test
	fun `a prebuild round-trip does not silently clear the failed-start tone`() {
		// A gradle-save-triggered project sync fires the prebuild; the warm build still runs
		// (pinned behaviour) but must not clear the tone on its way through - its outcome is
		// silent, and only a tap or a save is a user gesture.
		val prebuild = reducer.reduce(failedStartIdle(), SessionEvent.PrebuildRequested)
		assertThat(prebuild.state)
			.isEqualTo(QuickBuildSessionState.Prebuilding(lastStartFailed = true))
		assertThat(prebuild.effects).containsExactly(SessionEffect.StartProxyAppPrebuild)
		assertThat(QuickBuildStatus.from(prebuild.state).toTone()).isEqualTo(QuickBuildTone.ERROR)

		val finished = reducer.reduce(prebuild.state, SessionEvent.PrebuildFinished)
		assertThat(finished.state).isEqualTo(QuickBuildSessionState.Idle(lastStartFailed = true))
		assertThat(QuickBuildStatus.from(finished.state).toTone()).isEqualTo(QuickBuildTone.ERROR)
	}

	@Test
	fun `a tap queued on the warm build clears the tone and reads BUILDING`() {
		val prebuilding = reducer.reduce(failedStartIdle(), SessionEvent.PrebuildRequested).state

		val tapped = reducer.reduce(prebuilding, SessionEvent.QuickBuildTapped())

		assertThat(tapped.state)
			.isEqualTo(QuickBuildSessionState.Prebuilding(tapQueued = true))
		assertThat(QuickBuildStatus.from(tapped.state).toTone()).isEqualTo(QuickBuildTone.BUILDING)
	}

	@Test
	fun `a save during the warm build clears the tone without touching the build`() {
		val prebuilding = reducer.reduce(failedStartIdle(), SessionEvent.PrebuildRequested).state

		val saved = reducer.reduce(prebuilding, SessionEvent.FileSaved)

		assertThat(saved.state).isEqualTo(QuickBuildSessionState.Prebuilding())
		assertThat(saved.effects).isEmpty()
		assertThat(QuickBuildStatus.from(saved.state).toTone()).isEqualTo(QuickBuildTone.READY)
	}

	@Test
	fun `an explicit session teardown clears the failed-start tone`() {
		val transition = reducer.reduce(failedStartIdle(), SessionEvent.SessionRestartRequested)

		// Project close / Standard Run takeover: the tone must not survive into what follows.
		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Idle())
		assertThat(transition.effects).isEmpty()
	}
}
