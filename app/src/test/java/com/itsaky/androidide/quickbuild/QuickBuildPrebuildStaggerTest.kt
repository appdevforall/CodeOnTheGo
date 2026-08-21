package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.SessionEffect
import org.appdevforall.cotg.quickbuild.domain.session.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionReducer
import org.junit.Test

/**
 * The stagger contract (ADFA-4128 project-open ANR): the eager prebuild must NOT start inside
 * the project-open contention window, must start once the window passes, must not delay a live
 * session's variant-reprovision check, and must never make a user tap wait - a tap from Idle
 * provisions immediately whether or not a prebuild was ever scheduled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickBuildPrebuildStaggerTest {
	private var fires = 0

	private fun TestScope.stagger(): QuickBuildPrebuildStagger =
		QuickBuildPrebuildStagger(
			scope = backgroundScope,
			staggerMillis = STAGGER,
		)

	@Test
	fun `no prebuild inside the stagger window`() =
		runTest {
			stagger().onProjectSynced(sessionIsLive = { false }, fire = { fires++ })
			runCurrent()
			assertThat(fires).isEqualTo(0)

			advanceTimeBy(STAGGER - 1)
			runCurrent()
			assertThat(fires).isEqualTo(0)
		}

	@Test
	fun `the prebuild fires exactly once after the window`() =
		runTest {
			stagger().onProjectSynced(sessionIsLive = { false }, fire = { fires++ })

			advanceTimeBy(STAGGER + 1)
			runCurrent()
			assertThat(fires).isEqualTo(1)

			// The window fired and is spent; time alone must not fire it again.
			advanceTimeBy(STAGGER * 10)
			runCurrent()
			assertThat(fires).isEqualTo(1)
		}

	@Test
	fun `a live session bypasses the window - the variant reprovision check cannot wait`() =
		runTest {
			stagger().onProjectSynced(sessionIsLive = { true }, fire = { fires++ })
			assertThat(fires).isEqualTo(1)
		}

	@Test
	fun `a re-sync during the window replaces the pending prebuild instead of stacking one`() =
		runTest {
			val stagger = stagger()
			stagger.onProjectSynced(sessionIsLive = { false }, fire = { fires++ })
			advanceTimeBy(STAGGER / 2)

			stagger.onProjectSynced(sessionIsLive = { false }, fire = { fires++ })

			// The first window's deadline passes; the replaced schedule must not fire.
			advanceTimeBy(STAGGER / 2 + 1)
			runCurrent()
			assertThat(fires).isEqualTo(0)

			// The second window's own deadline releases exactly one fire.
			advanceTimeBy(STAGGER / 2)
			runCurrent()
			assertThat(fires).isEqualTo(1)
		}

	@Test
	fun `a re-sync during the window with a now-live session fires through immediately`() =
		runTest {
			val stagger = stagger()
			stagger.onProjectSynced(sessionIsLive = { false }, fire = { fires++ })
			advanceTimeBy(STAGGER / 2)

			// The user tapped during the window: the session is live by the next sync, whose
			// reprovision check must not wait - and the stale scheduled prebuild is dropped.
			stagger.onProjectSynced(sessionIsLive = { true }, fire = { fires++ })
			assertThat(fires).isEqualTo(1)

			advanceTimeBy(STAGGER * 10)
			runCurrent()
			assertThat(fires).isEqualTo(1)
		}

	@Test
	fun `cancelling the scope drops a pending prebuild`() =
		runTest {
			stagger().onProjectSynced(sessionIsLive = { false }, fire = { fires++ })
			backgroundScope.cancel()

			advanceTimeBy(STAGGER * 10)
			runCurrent()
			assertThat(fires).isEqualTo(0)
		}

	/**
	 * The constraint the stagger leans on without owning: taps do not route through it, and
	 * from Idle - the state the whole stagger window sits in - a tap provisions IMMEDIATELY.
	 * Pinned against the real reducer so a routing change that made taps wait for the
	 * deferred prebuild would go red here.
	 */
	@Test
	fun `a tap during the window provisions immediately - deferral never gates the user`() {
		val transition =
			SessionReducer().reduce(
				QuickBuildSessionState.Idle(),
				SessionEvent.QuickBuildTapped(),
			)

		assertThat(transition.state).isInstanceOf(QuickBuildSessionState.Provisioning::class.java)
		assertThat(transition.effects).containsExactly(SessionEffect.StartProvisioning)
	}

	/**
	 * The comparison that makes the stagger a strict improvement for an early tap: under the
	 * OLD eager trigger the same tap landed in Prebuilding and had to queue behind the warm
	 * build. Kept next to the test above so the tradeoff stays written down as behavior.
	 */
	@Test
	fun `a tap mid-prebuild still queues - the window is the only tap-friendly gap`() {
		val transition =
			SessionReducer().reduce(
				QuickBuildSessionState.Prebuilding(),
				SessionEvent.QuickBuildTapped(),
			)

		assertThat(transition.state).isEqualTo(QuickBuildSessionState.Prebuilding(tapQueued = true))
		assertThat(transition.effects).isEmpty()
	}

	companion object {
		private const val STAGGER = 30_000L
	}
}
