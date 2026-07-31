package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.junit.Test

/**
 * Pure JVM: the composite's contract (fan-out + failure isolation) is verified with
 * recording fakes, no `org.json` and no Android runtime needed.
 */
class CompositeQuickBuildMetricsSinkTest {
	private class RecordingSink(
		private val throwOnSession: Boolean = false,
	) : QuickBuildMetricsSink {
		val calls = mutableListOf<String>()

		override fun onSessionStarted() {
			if (throwOnSession) throw RuntimeException("boom")
			calls += "session"
		}

		override fun onBuildStarted(
			buildId: Long,
			route: BuildRoute,
			changes: ChangedFiles,
		) {
			calls += "started"
		}

		override fun onBuildFinished(
			buildId: Long,
			outcome: BuildOutcome,
		) {
			calls += "finished"
		}

		override fun onReloadTimeline(timeline: E2eTimeline) {
			calls += "reload"
		}

		override fun onInvalidation(reason: InvalidationReason) {
			calls += "invalidation"
		}

		override fun onProxyAppRebuild(
			isSuccess: Boolean,
			durationMillis: Long,
		) {
			calls += "rebaseline"
		}
	}

	private val timeline = E2eTimeline(generation = 1, trigger = 0, compileDone = 10, deploySent = 12, reloadLive = 20)

	@Test
	fun `fans every callback out to all delegates, in order`() {
		val a = RecordingSink()
		val b = RecordingSink()
		val composite = CompositeQuickBuildMetricsSink(a, b)

		composite.onSessionStarted()
		composite.onBuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		composite.onReloadTimeline(timeline)

		assertThat(a.calls).containsExactly("session", "started", "reload").inOrder()
		assertThat(b.calls).containsExactly("session", "started", "reload").inOrder()
	}

	@Test
	fun `a throwing delegate does not stop the others`() {
		val bad = RecordingSink(throwOnSession = true)
		val good = RecordingSink()

		// Must not propagate the delegate's exception.
		CompositeQuickBuildMetricsSink(bad, good).onSessionStarted()

		assertThat(good.calls).containsExactly("session")
	}

	@Test
	fun `an interface-default event still reaches the delegates`() {
		val a = RecordingSink()

		// onReloadTimeline is a defaulted interface method; the composite must override it
		// so the delegate's implementation is still invoked.
		CompositeQuickBuildMetricsSink(a).onReloadTimeline(timeline)

		assertThat(a.calls).containsExactly("reload")
	}
}
