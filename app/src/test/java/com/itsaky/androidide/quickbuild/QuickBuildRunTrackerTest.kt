package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.services.QuickBuildResult
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.junit.Test

/** How one plugin-started Quick Build's result is read off the session's status (ADFA-6493). */
class QuickBuildRunTrackerTest {
	private fun tracker(start: QuickBuildStatus) = QuickBuildRunTracker(start, startedAtMillis = 1_000L, buildOutput = { "the log" })

	/** Feeds [statuses] in order; the result of the last one, or null if none settled. */
	private fun QuickBuildRunTracker.run(vararg statuses: QuickBuildStatus): QuickBuildResult? {
		var result: QuickBuildResult? = null
		for (status in statuses) {
			check(result == null) { "settled before $status" }
			result = onStatus(status, nowMillis = 4_000L)
		}
		return result
	}

	private val upToDate = QuickBuildStatus.UpToDate(generation = 3L, buildDurationMillis = 900L)

	@Test
	fun `a first tap that provisions reports a restart`() {
		val tracker = tracker(QuickBuildStatus.Hidden())

		val result = tracker.run(QuickBuildStatus.Hidden(), QuickBuildStatus.Provisioning(), QuickBuildStatus.UpToDate(1L, null))

		assertThat(result).isEqualTo(QuickBuildResult.Reloaded(durationMillis = 3_000L, restarted = true))
	}

	@Test
	fun `a live build that lands reports a reload with its time`() {
		val tracker = tracker(upToDate)

		val result = tracker.run(upToDate, QuickBuildStatus.Building(3L), QuickBuildStatus.UpToDate(4L, 1_200L))

		assertThat(result).isEqualTo(QuickBuildResult.Reloaded(durationMillis = 3_000L, restarted = false))
	}

	@Test
	fun `a landing that relaunched the app reports a restart`() {
		val tracker = tracker(upToDate)

		val result = tracker.run(QuickBuildStatus.Building(3L), QuickBuildStatus.UpToDate(4L, 1_200L, restarted = true))

		assertThat(result).isEqualTo(QuickBuildResult.Reloaded(durationMillis = 3_000L, restarted = true))
	}

	@Test
	fun `a landing is recognised even when the conflated status skipped Building`() {
		val tracker = tracker(upToDate)

		assertThat(tracker.run(QuickBuildStatus.UpToDate(4L, 1_200L))).isInstanceOf(QuickBuildResult.Reloaded::class.java)
	}

	@Test
	fun `the unchanged start status waits for the quiet window`() {
		val tracker = tracker(upToDate)

		assertThat(tracker.run(upToDate)).isNull()
		assertThat(tracker.awaitingQuiet).isTrue()
		assertThat(tracker.onQuiet(upToDate)).isEqualTo(QuickBuildResult.UpToDate)
	}

	@Test
	fun `a build in flight holds off the quiet window`() {
		val tracker = tracker(upToDate)

		tracker.run(QuickBuildStatus.Building(3L))

		assertThat(tracker.awaitingQuiet).isFalse()
	}

	@Test
	fun `a compile error fails with the errors and the build output`() {
		val tracker = tracker(upToDate)
		val failure =
			SessionFailure.CompileError(
				listOf(
					BuildDiagnostic(BuildDiagnostic.Severity.WARNING, "unused", "/p/A.kt", 1, 1),
					BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "Unresolved reference: foo", "/p/B.kt", 12, 5),
				),
			)

		val result = tracker.run(QuickBuildStatus.Building(3L), QuickBuildStatus.Failed(3L, failure))

		assertThat(result)
			.isEqualTo(QuickBuildResult.Failed("The change did not compile:\n/p/B.kt:12:5: Unresolved reference: foo", "the log"))
	}

	@Test
	fun `the same failure as before the tap is reported once the tap rebuilt it`() {
		val failed = QuickBuildStatus.Failed(3L, SessionFailure.ProxyAppCrash("NPE"))
		val tracker = tracker(failed)

		val result = tracker.run(failed, QuickBuildStatus.Building(3L), failed)

		assertThat(result).isEqualTo(QuickBuildResult.Failed("The app crashed after the reload: NPE", "the log"))
	}

	@Test
	fun `a tap with nothing to build after a failure reports that failure`() {
		val failed = QuickBuildStatus.Failed(3L, SessionFailure.DeployError("could not deliver"))
		val tracker = tracker(failed)

		assertThat(tracker.run(failed)).isNull()
		assertThat(tracker.onQuiet(failed)).isEqualTo(QuickBuildResult.Failed("could not deliver", "the log"))
	}

	@Test
	fun `an app that is not running reports so rather than a failure`() {
		val tracker = tracker(upToDate)

		val result =
			tracker.run(
				QuickBuildStatus.Building(3L),
				QuickBuildStatus.Failed(3L, SessionFailure.DeployError("Your app is not running.", appNotRunning = true)),
			)

		assertThat(result).isEqualTo(QuickBuildResult.AppNotRunning("Your app is not running."))
	}

	@Test
	fun `a stopped live build lands back on its generation as cancelled`() {
		val tracker = tracker(upToDate)

		val result = tracker.run(QuickBuildStatus.Building(3L), QuickBuildStatus.UpToDate(3L, null))

		assertThat(result).isEqualTo(QuickBuildResult.Cancelled)
	}

	@Test
	fun `a stopped first build drops the session as cancelled`() {
		val tracker = tracker(QuickBuildStatus.Hidden())

		val result = tracker.run(QuickBuildStatus.Provisioning(), QuickBuildStatus.Hidden())

		assertThat(result).isEqualTo(QuickBuildResult.Cancelled)
	}

	@Test
	fun `a cancel whose build the conflated status hid still reads as cancelled`() {
		val tracker = tracker(upToDate)
		tracker.onCancelRequested()

		assertThat(tracker.onQuiet(upToDate)).isEqualTo(QuickBuildResult.Cancelled)
	}

	@Test
	fun `a failed start fails, even when the session had failed to start before`() {
		val tracker = tracker(QuickBuildStatus.Hidden(lastStartFailed = true))

		val result = tracker.run(QuickBuildStatus.Provisioning(), QuickBuildStatus.Hidden(lastStartFailed = true))

		assertThat(result).isEqualTo(QuickBuildResult.Failed("Quick Build could not start.", "the log"))
	}

	@Test
	fun `a full build that parks fails`() {
		val tracker = tracker(upToDate)
		val reason = InvalidationReason.MANIFEST_CHANGED

		val result =
			tracker.run(
				QuickBuildStatus.NeedsFullBuild(reason, 3L),
				QuickBuildStatus.Provisioning(reason),
				QuickBuildStatus.NeedsFullBuild(reason, 3L, awaitingRetry = true),
			)

		assertThat(result).isInstanceOf(QuickBuildResult.Failed::class.java)
	}

	@Test
	fun `a stopped full build parks as cancelled, not as broken build files`() {
		val reason = InvalidationReason.MANIFEST_CHANGED
		val tracker = tracker(QuickBuildStatus.NeedsFullBuild(reason, 3L))
		tracker.onCancelRequested()

		val result =
			tracker.run(
				QuickBuildStatus.Provisioning(reason),
				QuickBuildStatus.NeedsFullBuild(reason, 3L, awaitingRetry = true),
			)

		assertThat(result).isEqualTo(QuickBuildResult.Cancelled)
	}

	@Test
	fun `a build the daemon took down is not cancelled by the respawn that re-runs it`() {
		val tracker = tracker(upToDate)

		val respawned =
			tracker.run(
				QuickBuildStatus.Building(3L),
				QuickBuildStatus.Reconnecting(3L),
				QuickBuildStatus.UpToDate(3L, null),
			)

		assertThat(respawned).isNull()
		assertThat(tracker.run(QuickBuildStatus.Building(3L), QuickBuildStatus.UpToDate(4L, 1_200L)))
			.isEqualTo(QuickBuildResult.Reloaded(durationMillis = 3_000L, restarted = false))
	}

	@Test
	fun `a full build that lands reports a restart`() {
		val tracker = tracker(upToDate)
		val reason = InvalidationReason.MANIFEST_CHANGED

		val result =
			tracker.run(
				QuickBuildStatus.NeedsFullBuild(reason, 3L),
				QuickBuildStatus.Provisioning(reason),
				QuickBuildStatus.UpToDate(9L, null),
			)

		assertThat(result).isEqualTo(QuickBuildResult.Reloaded(durationMillis = 3_000L, restarted = true))
	}

	@Test
	fun `a compiler that could not restart fails, and one that did waits for the quiet window`() {
		val tracker = tracker(QuickBuildStatus.Reconnecting(3L, restartFailed = true))

		assertThat(tracker.run(QuickBuildStatus.Reconnecting(3L))).isNull()
		assertThat(tracker.awaitingQuiet).isFalse()
		assertThat(tracker.run(QuickBuildStatus.Reconnecting(3L, restartFailed = true)))
			.isInstanceOf(QuickBuildResult.Failed::class.java)

		val recovered = tracker(QuickBuildStatus.Reconnecting(3L, restartFailed = true))
		assertThat(recovered.run(QuickBuildStatus.Reconnecting(3L), QuickBuildStatus.UpToDate(3L, null))).isNull()
		assertThat(recovered.awaitingQuiet).isTrue()
	}

	@Test
	fun `a park names whether the build or the install is what failed`() {
		val reason = InvalidationReason.MANIFEST_CHANGED
		val buildFailed =
			tracker(upToDate).run(
				QuickBuildStatus.NeedsFullBuild(reason, 3L),
				QuickBuildStatus.NeedsFullBuild(reason, 3L, awaitingRetry = true),
			)
		val installUnconfirmed =
			tracker(upToDate).run(
				QuickBuildStatus.Provisioning(reason),
				QuickBuildStatus.NeedsFullBuild(InvalidationReason.INSTALL_NOT_CONFIRMED, 3L, awaitingRetry = true),
			)

		assertThat((buildFailed as QuickBuildResult.Failed).reason).contains("full build failed")
		assertThat((installUnconfirmed as QuickBuildResult.Failed).reason).contains("install was not confirmed")
	}

	@Test
	fun `a retry the conflated status hid is still a park, not up to date`() {
		val parked = QuickBuildStatus.NeedsFullBuild(InvalidationReason.GRADLE_CONFIG_CHANGED, 3L, awaitingRetry = true)
		val tracker = tracker(parked)

		assertThat(tracker.run(parked)).isNull()
		assertThat(tracker.awaitingQuiet).isTrue()
		assertThat(tracker.onQuiet(parked)).isInstanceOf(QuickBuildResult.Failed::class.java)
	}

	@Test
	fun `a respawn the conflated status hid is still down, not up to date`() {
		val down = QuickBuildStatus.Reconnecting(3L, restartFailed = true)
		val tracker = tracker(down)

		assertThat(tracker.run(down)).isNull()
		assertThat(tracker.awaitingQuiet).isTrue()
		assertThat(tracker.onQuiet(down)).isInstanceOf(QuickBuildResult.Failed::class.java)
	}

	@Test
	fun `a compile error summary leaves out positions the compiler did not know`() {
		val errors =
			listOf(
				BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "a", file = "A.kt", line = 4, column = 2),
				BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "b", file = "B.kt", line = 0, column = 7),
				BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "c", file = "C.kt", line = 9, column = -1),
			)

		val lines = QuickBuildRunTracker.compileErrorSummary(errors).lines().drop(1)

		assertThat(lines).containsExactly("A.kt:4:2: a", "B.kt: b", "C.kt:9: c").inOrder()
	}

	@Test
	fun `a compile error summary names at most five errors`() {
		val errors = (1..7).map { BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "e$it") }

		val summary = QuickBuildRunTracker.compileErrorSummary(errors)

		assertThat(summary.lines()).hasSize(7)
		assertThat(summary).endsWith("e5\n(2 more)")
	}
}
