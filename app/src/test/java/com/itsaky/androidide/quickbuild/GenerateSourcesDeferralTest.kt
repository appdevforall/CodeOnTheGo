package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.junit.Test

/**
 * The deferral contract (quickbuild/docs/resource-updates.md): a resource save runs
 * `generateSources` immediately when no Quick Build session exists, parks it while one is live,
 * coalesces N saves into one request, and releases exactly one build when the pipeline settles
 * or the session ends - never dropping a parked request.
 *
 * "Released" is not "ran": `generateSources` refuses silently while any Gradle build is in
 * progress, including builds this class cannot see from session state. [attempts] counts every
 * call, [builds] only the ones that dispatched, and the gap between them is what the retry
 * behaviour is about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenerateSourcesDeferralTest {
	private var builds = 0
	private var attempts = 0
	private var dispatch = true

	private fun TestScope.deferral(): GenerateSourcesDeferral =
		GenerateSourcesDeferral(
			scope = backgroundScope,
			runBuild = {
				attempts++
				if (dispatch) builds++
				dispatch
			},
			idleGraceMillis = GRACE,
		)

	@Test
	fun `no session runs immediately, attached or not`() =
		runTest {
			val deferral = deferral()

			// Never attached: Quick Build was never started this process.
			deferral.onResourceSaved()
			assertThat(builds).isEqualTo(1)

			// Attached but the session is Idle: still today's immediate call.
			val state = MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Idle())
			deferral.attach(state)
			runCurrent()
			deferral.onResourceSaved()
			assertThat(builds).isEqualTo(2)
		}

	@Test
	fun `a building session parks the save for as long as it stays busy`() =
		runTest {
			val deferral = deferral()
			val state =
				MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Building(1L))
			deferral.attach(state)
			runCurrent()

			deferral.onResourceSaved()
			runCurrent()
			assertThat(builds).isEqualTo(0)

			// Busy states hold with no timer: time alone must not release the request.
			advanceTimeBy(GRACE * 100)
			runCurrent()
			assertThat(builds).isEqualTo(0)
		}

	@Test
	fun `idle transition after several saves releases exactly one build`() =
		runTest {
			val deferral = deferral()
			val state =
				MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Building(1L))
			deferral.attach(state)
			runCurrent()

			repeat(3) { deferral.onResourceSaved() }
			runCurrent()
			assertThat(builds).isEqualTo(0)

			state.value = QuickBuildSessionState.Deployed(generation = 2L, buildDurationMillis = 500L)
			runCurrent()
			// Not yet: the settle window must pass first.
			advanceTimeBy(GRACE - 1)
			runCurrent()
			assertThat(builds).isEqualTo(0)

			advanceTimeBy(1)
			runCurrent()
			assertThat(builds).isEqualTo(1)

			// Coalesced for good: nothing else fires later.
			advanceTimeBy(GRACE * 100)
			runCurrent()
			assertThat(builds).isEqualTo(1)
		}

	@Test
	fun `save during an active-but-idle session waits out the grace window`() =
		runTest {
			val deferral = deferral()
			val state =
				MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Ready(1L))
			deferral.attach(state)
			runCurrent()

			// The primary trap: at save time the watcher batch is still inside its debounce,
			// so the session looks idle. The request must not fire right away.
			deferral.onResourceSaved()
			runCurrent()
			assertThat(builds).isEqualTo(0)

			// A build starting inside the window cancels the pending release...
			advanceTimeBy(GRACE - 1)
			state.value = QuickBuildSessionState.Building(1L)
			runCurrent()
			advanceTimeBy(GRACE * 10)
			runCurrent()
			assertThat(builds).isEqualTo(0)

			// ...and the release happens one settle window after the build lands.
			state.value = QuickBuildSessionState.Deployed(generation = 2L, buildDurationMillis = 500L)
			runCurrent()
			advanceTimeBy(GRACE)
			runCurrent()
			assertThat(builds).isEqualTo(1)
		}

	@Test
	fun `a session ending with a parked request runs it instead of dropping it`() =
		runTest {
			val deferral = deferral()
			val state =
				MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Provisioning())
			deferral.attach(state)
			runCurrent()

			deferral.onResourceSaved()
			runCurrent()
			assertThat(builds).isEqualTo(0)

			// Teardown to Idle releases immediately - no grace, nothing left to contend with.
			state.value = QuickBuildSessionState.Idle()
			runCurrent()
			assertThat(builds).isEqualTo(1)
		}

	@Test
	fun `re-attach does not double-subscribe and a replaced stream stops driving it`() =
		runTest {
			val deferral = deferral()
			val first = MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Idle())
			deferral.attach(first)
			deferral.attach(first)
			runCurrent()
			assertThat(first.subscriptionCount.value).isEqualTo(1)

			val second =
				MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Building(1L))
			deferral.attach(second)
			runCurrent()
			assertThat(first.subscriptionCount.value).isEqualTo(0)

			deferral.onResourceSaved()
			runCurrent()
			assertThat(builds).isEqualTo(0)

			// The old stream must be inert: its transitions release nothing.
			first.value = QuickBuildSessionState.Building(1L)
			runCurrent()
			first.value = QuickBuildSessionState.Idle()
			runCurrent()
			assertThat(builds).isEqualTo(0)

			second.value = QuickBuildSessionState.Idle()
			runCurrent()
			assertThat(builds).isEqualTo(1)
		}

	@Test
	fun `a refused build stays parked and retries until it dispatches`() =
		runTest {
			val deferral = deferral()
			val state =
				MutableStateFlow<QuickBuildSessionState>(QuickBuildSessionState.Ready(1L))
			deferral.attach(state)
			runCurrent()

			// Someone else owns the single Gradle slot - a project sync, or the user's own Run.
			// Session state says settled, so the release fires and generateSources refuses it.
			dispatch = false
			deferral.onResourceSaved()
			advanceTimeBy(GRACE)
			runCurrent()
			assertThat(attempts).isEqualTo(1)
			assertThat(builds).isEqualTo(0)

			// The request is still owed: it tries again rather than being dropped.
			advanceTimeBy(GRACE)
			runCurrent()
			assertThat(attempts).isEqualTo(2)
			assertThat(builds).isEqualTo(0)

			// The slot frees up and the same parked request finally lands.
			dispatch = true
			advanceTimeBy(GRACE)
			runCurrent()
			assertThat(builds).isEqualTo(1)

			// And is then done: no straggler from the retry chain.
			advanceTimeBy(GRACE * 100)
			runCurrent()
			assertThat(builds).isEqualTo(1)
			assertThat(attempts).isEqualTo(3)
		}

	@Test
	fun `a refusal with no session at all is retried too`() =
		runTest {
			// The immediate path: no Quick Build session, so the save runs straight away - and
			// can be refused just the same. It must not be a fire-and-forget.
			val deferral = deferral()
			dispatch = false
			deferral.onResourceSaved()
			runCurrent()
			assertThat(attempts).isEqualTo(1)
			assertThat(builds).isEqualTo(0)

			dispatch = true
			advanceTimeBy(GRACE)
			runCurrent()
			assertThat(builds).isEqualTo(1)
		}

	@Test
	fun `a durable refusal gives up instead of retrying forever`() =
		runTest {
			// No build service or a dead tooling server refuses every time. Retrying past the
			// span of an ordinary build is burning timers, not waiting for a slot.
			val deferral = deferral()
			dispatch = false
			deferral.onResourceSaved()
			advanceTimeBy(GRACE * 100)
			runCurrent()

			assertThat(builds).isEqualTo(0)
			assertThat(attempts).isEqualTo(MAX_ATTEMPTS)

			// Given up, not wedged: a later save starts a fresh request.
			dispatch = true
			deferral.onResourceSaved()
			runCurrent()
			assertThat(builds).isEqualTo(1)
		}

	companion object {
		private const val GRACE = 3_000L

		/** One initial release plus GenerateSourcesDeferral's MAX_REFUSALS retries. */
		private const val MAX_ATTEMPTS = 6
	}
}
