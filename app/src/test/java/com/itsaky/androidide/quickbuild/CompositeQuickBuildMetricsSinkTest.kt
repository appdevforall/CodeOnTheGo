package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
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
			relaunchOk: Boolean,
			toRunningMillis: Long?,
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

	/**
	 * Every callback, not just the three above: an un-overridden method falls back to the
	 * interface default, which drops the event for every delegate at once. Only calling
	 * each one can see that.
	 */
	@Test
	fun `all six callbacks reach the delegates`() {
		val a = RecordingSink()
		val composite = CompositeQuickBuildMetricsSink(a)

		composite.onSessionStarted()
		composite.onBuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		composite.onBuildFinished(1, BuildOutcome.Success(1, 10))
		composite.onReloadTimeline(timeline)
		composite.onInvalidation(InvalidationReason.MANIFEST_CHANGED)
		composite.onProxyAppRebuild(isSuccess = true, durationMillis = 42, relaunchOk = true, toRunningMillis = 99)

		assertThat(a.calls)
			.containsExactly("session", "started", "finished", "reload", "invalidation", "rebaseline")
			.inOrder()
	}

	/**
	 * Failure isolation has to hold on every callback, not only the one the original test
	 * happened to throw from - each is a separate `fanOut` call site.
	 */
	@Test
	fun `a delegate that throws on every callback never breaks the others`() {
		val bad =
			object : QuickBuildMetricsSink {
				override fun onSessionStarted() = throw RuntimeException("boom")

				override fun onBuildStarted(
					buildId: Long,
					route: BuildRoute,
					changes: ChangedFiles,
				) = throw RuntimeException("boom")

				override fun onBuildFinished(
					buildId: Long,
					outcome: BuildOutcome,
				) = throw RuntimeException("boom")

				override fun onReloadTimeline(timeline: E2eTimeline) = throw RuntimeException("boom")

				override fun onInvalidation(reason: InvalidationReason) = throw RuntimeException("boom")

				override fun onProxyAppRebuild(
					isSuccess: Boolean,
					durationMillis: Long,
					relaunchOk: Boolean,
					toRunningMillis: Long?,
				) = throw RuntimeException("boom")
			}
		val good = RecordingSink()
		val composite = CompositeQuickBuildMetricsSink(bad, good)

		composite.onSessionStarted()
		composite.onBuildStarted(1, BuildRoute.CodeOnly, ChangedFiles.Known(emptySet()))
		composite.onBuildFinished(1, BuildOutcome.Success(1, 10))
		composite.onReloadTimeline(timeline)
		composite.onInvalidation(InvalidationReason.MANIFEST_CHANGED)
		composite.onProxyAppRebuild(isSuccess = false, durationMillis = 0, relaunchOk = false, toRunningMillis = null)

		assertThat(good.calls)
			.containsExactly("session", "started", "finished", "reload", "invalidation", "rebaseline")
			.inOrder()
	}

	/** No delegates is a legal configuration (metrics off); it must be a silent no-op. */
	@Test
	fun `a composite with no delegates does nothing rather than throwing`() {
		val composite = CompositeQuickBuildMetricsSink()

		composite.onSessionStarted()
		composite.onBuildFinished(1, BuildOutcome.Success(1, 10))
		composite.onProxyAppRebuild(isSuccess = true, durationMillis = 1, relaunchOk = false, toRunningMillis = null)
	}
}
