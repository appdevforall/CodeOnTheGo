package org.appdevforall.cotg.quickbuild.domain.session

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.junit.jupiter.api.Test

class QuickBuildToneTest {
	@Test
	fun `hidden and up-to-date map to READY`() {
		assertThat(QuickBuildStatus.Hidden().toTone()).isEqualTo(QuickBuildTone.READY)
		assertThat(QuickBuildStatus.UpToDate(1, null).toTone()).isEqualTo(QuickBuildTone.READY)
		assertThat(QuickBuildStatus.UpToDate(1, 500).toTone()).isEqualTo(QuickBuildTone.READY)
	}

	@Test
	fun `provisioning and building map to BUILDING`() {
		assertThat(QuickBuildStatus.Provisioning().toTone()).isEqualTo(QuickBuildTone.BUILDING)
		assertThat(QuickBuildStatus.Building(1).toTone()).isEqualTo(QuickBuildTone.BUILDING)
	}

	// Behaviour 1 draws a line: the button offers a stop for builds the USER started, and
	// keeps the bolt for the two background builds they did not. These are the derivations
	// that decide it, so they are pinned rather than left to be re-decided by accident.
	@Test
	fun `the background warm compile reads as READY - it deploys nothing and was never asked for`() {
		val warmCompiling = QuickBuildSessionState.Building(3, warmingCompiler = true)

		assertThat(QuickBuildStatus.from(warmCompiling).toTone()).isEqualTo(QuickBuildTone.READY)
	}

	@Test
	fun `an unasked-for prebuild reads as READY, but a prebuild with a queued tap reads as BUILDING`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prebuilding()).toTone())
			.isEqualTo(QuickBuildTone.READY)
		// Once a tap is queued the user IS waiting on this build, so the stop belongs to them.
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Prebuilding(tapQueued = true)).toTone())
			.isEqualTo(QuickBuildTone.BUILDING)
	}

	@Test
	fun `a real quick build reads as BUILDING so the button becomes the stop button`() {
		assertThat(QuickBuildStatus.from(QuickBuildSessionState.Building(3)).toTone())
			.isEqualTo(QuickBuildTone.BUILDING)
	}

	@Test
	fun `only a real failure reads as ERROR`() {
		val failure = SessionFailure.DeployError("boom")
		assertThat(QuickBuildStatus.Failed(1, failure).toTone()).isEqualTo(QuickBuildTone.ERROR)
	}

	@Test
	fun `needing a full build is SLOW, not an error - it is ordinary work`() {
		assertThat(
			QuickBuildStatus.NeedsFullBuild(InvalidationReason.MANIFEST_CHANGED, 1).toTone(),
		).isEqualTo(QuickBuildTone.SLOW)
		// End to end from the session state: a plain invalidation still reads as SLOW.
		assertThat(
			QuickBuildStatus
				.from(
					QuickBuildSessionState.Invalidated(InvalidationReason.MANIFEST_CHANGED, 1),
				).toTone(),
		).isEqualTo(QuickBuildTone.SLOW)
	}

	@Test
	fun `a rebaseline that failed and parked IS an error - only the user moves it`() {
		// SLOW is documented as "not a failure", but a parked rebaseline is one: the manual QA
		// bug was a failed rebaseline showing the same hollow bolt as ordinary upcoming work.
		assertThat(
			QuickBuildStatus
				.from(
					QuickBuildSessionState.Invalidated(
						InvalidationReason.GRADLE_CONFIG_CHANGED,
						1,
						awaitingRetry = true,
					),
				).toTone(),
		).isEqualTo(QuickBuildTone.ERROR)
	}

	@Test
	fun `a daemon respawn is RECONNECTING, not an error - it resolves itself`() {
		assertThat(QuickBuildStatus.Reconnecting(1).toTone()).isEqualTo(QuickBuildTone.RECONNECTING)
	}

	@Test
	fun `a respawn that failed IS an error - it does not resolve itself`() {
		// RECONNECTING is documented as transient work with nothing to do. After a failed
		// respawn the compiler stays down until the user taps, which is what ERROR means.
		assertThat(QuickBuildStatus.Reconnecting(1, restartFailed = true).toTone())
			.isEqualTo(QuickBuildTone.ERROR)
	}

	/**
	 * The regression this split exists to prevent: three unlike states all rendering as the
	 * one red icon, so "something broke" was claimed far more often than anything had.
	 */
	@Test
	fun `no status other than a failure claims the error tone`() {
		val nonFailures =
			listOf(
				QuickBuildStatus.Hidden(),
				QuickBuildStatus.UpToDate(1, null),
				QuickBuildStatus.Provisioning(),
				QuickBuildStatus.Building(1),
				QuickBuildStatus.NeedsFullBuild(InvalidationReason.MANIFEST_CHANGED, 1),
				QuickBuildStatus.Reconnecting(1),
			)

		nonFailures.forEach { status ->
			assertThat(status.toTone()).isNotEqualTo(QuickBuildTone.ERROR)
		}
	}

	/**
	 * Only [QuickBuildTone.BUILDING] makes a tap cancel (QuickBuildAction.execAction keys off
	 * exactly this), so a state the user cannot cancel must never claim it - a tap in
	 * Reconnecting would otherwise dispatch CancelRequested with no build to cancel.
	 */
	@Test
	fun `reconnecting does not claim the BUILDING tone, which would make a tap cancel`() {
		assertThat(QuickBuildStatus.Reconnecting(1).toTone()).isNotEqualTo(QuickBuildTone.BUILDING)
	}
}
