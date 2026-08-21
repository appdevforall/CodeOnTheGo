package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.session.SessionReducer
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.junit.Test

/**
 * What a user finds in the Build Output pane after a Quick Build session runs.
 *
 * The mapper's whole job is deciding what is *news*: [QuickBuildStatus] is derived from session
 * state, so the same status arrives repeatedly and a naive "print the status" would spam the pane.
 * These pin both halves - the lines that must appear (a failure's diagnostics above all, since
 * they carry the file:line the user needs) and the repeats that must not.
 */
class QuickBuildOutputLinesTest {
	private fun lines(
		previous: QuickBuildStatus?,
		current: QuickBuildStatus,
	) = quickBuildOutputLines(previous, current)

	private fun compileError(vararg diagnostics: BuildDiagnostic) =
		QuickBuildStatus.Failed(4L, SessionFailure.CompileError(diagnostics.toList()))

	@Test
	fun `the first emission says nothing`() {
		// It is the state the session was already in - narrating it would invent history.
		assertThat(lines(null, QuickBuildStatus.Provisioning())).isEmpty()
		assertThat(lines(null, compileError(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "x"))))
			.isEmpty()
	}

	@Test
	fun `an unchanged status says nothing`() {
		val status = QuickBuildStatus.Building(3L)
		assertThat(lines(status, status)).isEmpty()
	}

	@Test
	fun `every line is prefixed and newline-terminated`() {
		val emitted =
			lines(
				QuickBuildStatus.Building(4L),
				compileError(
					BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "boom", "/p/Foo.kt", 12, 5),
				),
			)

		assertThat(emitted).hasSize(2)
		emitted.forEach {
			assertThat(it).startsWith("Quick Build: ")
			assertThat(it).endsWith("\n")
		}
	}

	@Test
	fun `a compile failure prints every diagnostic with its location`() {
		val emitted =
			lines(
				QuickBuildStatus.Building(4L),
				compileError(
					BuildDiagnostic(
						BuildDiagnostic.Severity.ERROR,
						"Unresolved reference: foo",
						"/p/src/Foo.kt",
						12,
						5,
					),
					BuildDiagnostic(BuildDiagnostic.Severity.WARNING, "unused", "/p/src/Bar.kt", 3),
				),
			).joinToString("")

		assertThat(emitted).contains("build failed.")
		assertThat(emitted).contains("/p/src/Foo.kt:12:5: error: Unresolved reference: foo")
		// A column the compiler did not name must not render as a stray separator.
		assertThat(emitted).contains("/p/src/Bar.kt:3: warning: unused")
	}

	@Test
	fun `a diagnostic without a location still prints its message`() {
		// No dangling ':' where the location would have been, and no "null".
		val emitted =
			lines(
				QuickBuildStatus.Building(4L),
				compileError(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "no location")),
			)

		assertThat(emitted.last()).isEqualTo("Quick Build:   error: no location\n")
	}

	@Test
	fun `the same failure settling does not print twice`() {
		// A failure arrives as Building -> Failed and then settles Ready -> Failed with the
		// same content; printing both would double every error in the pane.
		val failure =
			SessionFailure.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "boom")),
			)
		val first = QuickBuildStatus.Failed(4L, failure)
		val settled = QuickBuildStatus.Failed(5L, failure)

		assertThat(lines(QuickBuildStatus.Building(4L), first)).isNotEmpty()
		assertThat(lines(first, settled)).isEmpty()
	}

	@Test
	fun `a new failure after the previous one does print`() {
		val first = compileError(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "first"))
		val second = compileError(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "second"))

		assertThat(lines(first, second).joinToString("")).contains("second")
	}

	@Test
	fun `a deploy failure and a crash each name what happened`() {
		val deploy =
			lines(
				QuickBuildStatus.Building(4L),
				QuickBuildStatus.Failed(4L, SessionFailure.DeployError("no space left")),
			).joinToString("")
		val crash =
			lines(
				QuickBuildStatus.Building(4L),
				QuickBuildStatus.Failed(4L, SessionFailure.ProxyAppCrash("NullPointerException")),
			).joinToString("")

		assertThat(deploy).contains("no space left")
		assertThat(crash).contains("NullPointerException")
		assertThat(crash).contains("last working version")
	}

	@Test
	fun `provisioning and the session opening are each announced once`() {
		assertThat(lines(QuickBuildStatus.Hidden(), QuickBuildStatus.Provisioning()).joinToString(""))
			.contains("running the initial full build")

		val ready =
			lines(
				QuickBuildStatus.Provisioning(),
				QuickBuildStatus.UpToDate(1L, buildDurationMillis = null),
			).joinToString("")
		assertThat(ready).contains("session ready")
		assertThat(ready).contains("generation 1")
	}

	@Test
	fun `an adopted session is announced too`() {
		// Adoption skips Provisioning entirely - the app is already installed and running.
		assertThat(
			lines(QuickBuildStatus.Hidden(), QuickBuildStatus.UpToDate(7L, buildDurationMillis = null))
				.joinToString(""),
		).contains("session ready, running generation 7")
	}

	@Test
	fun `a landed build reports its generation and duration`() {
		val emitted =
			lines(
				QuickBuildStatus.Building(4L),
				QuickBuildStatus.UpToDate(5L, buildDurationMillis = 1200L),
			).joinToString("")

		assertThat(emitted).contains("generation 5")
		assertThat(emitted).contains("1.2s")
		assertThat(emitted).contains("reloaded")
	}

	@Test
	fun `the landed line and the timing line report the same number for one loop`() {
		// A timing line reading "(total 3.9s)" next to a landed line reading "in 1948ms"
		// leaves the reader to work out which number is the loop. Both lines carry the
		// loop's own total, in the same format, so there is nothing to reconcile.
		val loop =
			E2eTimeline(
				generation = 10L,
				trigger = 0L,
				compileDone = 3_850L,
				deploySent = 3_860L,
				reloadLive = 3_894L,
				spans =
					E2eTimeline.HostSpans(
						queueMillis = 1_950L,
						compileRpcMillis = 1_800L,
						dexRpcMillis = 100L,
					),
			)

		val timing = quickBuildTimingLine(loop)!!
		val landed =
			lines(
				QuickBuildStatus.Building(9L),
				QuickBuildStatus.UpToDate(10L, buildDurationMillis = loop.totalMillis),
			).joinToString("")

		assertThat(timing).contains("3.9s from save to live")
		assertThat(landed).contains("reloaded to generation 10 in 3.9s")
		assertThat(landed).doesNotContain("ms")
	}

	@Test
	fun `a restarting deploy says so rather than calling itself a reload`() {
		val emitted =
			lines(
				QuickBuildStatus.Building(4L),
				QuickBuildStatus.UpToDate(5L, buildDurationMillis = 900L, restarted = true),
			).joinToString("")

		assertThat(emitted).contains("restarted")
		assertThat(emitted).doesNotContain("reloaded")
	}

	@Test
	fun `an up-to-date status with no build behind it says nothing`() {
		// The settle after a deploy, and the warm compile that deploys nothing: both would
		// otherwise print a second line for a build that already reported itself.
		assertThat(
			lines(
				QuickBuildStatus.UpToDate(5L, buildDurationMillis = 1200L),
				QuickBuildStatus.UpToDate(5L, buildDurationMillis = null),
			),
		).isEmpty()
	}

	@Test
	fun `a build starting names the generation still on screen`() {
		assertThat(
			lines(QuickBuildStatus.UpToDate(4L, buildDurationMillis = null), QuickBuildStatus.Building(4L))
				.joinToString(""),
		).contains("running generation 4")
	}

	@Test
	fun `invalidation reads as information and names a next step`() {
		val emitted =
			lines(
				QuickBuildStatus.UpToDate(4L, buildDurationMillis = null),
				QuickBuildStatus.NeedsFullBuild(InvalidationReason.MANIFEST_CHANGED, 4L),
			).joinToString("")

		assertThat(emitted).contains("the manifest changed")
		assertThat(emitted).contains("Tap Quick Build")
		// Not a failure - a full build is the normal answer to an unabsorbable edit.
		assertThat(emitted).doesNotContain("failed")
	}

	@Test
	fun `a parked rebaseline reads as a failure and names the save that retries`() {
		// The rebuild already ran and failed; narrating upcoming work here contradicts the
		// error bolt and the Gradle failure quoted just above. A save with a fix retries by
		// itself, so that is the gesture to name.
		val emitted =
			lines(
				QuickBuildStatus.Provisioning(InvalidationReason.MANIFEST_CHANGED),
				QuickBuildStatus.NeedsFullBuild(
					InvalidationReason.MANIFEST_CHANGED,
					4L,
					awaitingRetry = true,
				),
			).joinToString("")

		assertThat(emitted).contains("failed")
		assertThat(emitted).contains("save a fix")
		assertThat(emitted).doesNotContain("a full build is needed")
	}

	@Test
	fun `every invalidation reason has its own words`() {
		val rendered =
			InvalidationReason.values().map { reason ->
				lines(
					QuickBuildStatus.UpToDate(4L, buildDurationMillis = null),
					QuickBuildStatus.NeedsFullBuild(reason, 4L),
				).single()
			}

		assertThat(rendered).containsNoDuplicates()
		rendered.forEach { assertThat(it).doesNotContain("_") }
	}

	@Test
	fun `a daemon outage and its recovery are both narrated`() {
		val died =
			lines(QuickBuildStatus.Building(4L), QuickBuildStatus.Reconnecting(4L)).joinToString("")
		val back =
			lines(
				QuickBuildStatus.Reconnecting(4L),
				QuickBuildStatus.UpToDate(4L, buildDurationMillis = null),
			).joinToString("")

		assertThat(died).contains("compile daemon stopped")
		assertThat(back).contains("compile daemon is back")
	}

	@Test
	fun `a respawn that failed is narrated instead of a restart that is not happening`() {
		val failed =
			lines(
				QuickBuildStatus.Reconnecting(4L),
				QuickBuildStatus.Reconnecting(4L, restartFailed = true),
			).joinToString("")

		// "restarting it" is the claim that has to go: nothing is.
		assertThat(failed).contains("could not be restarted")
		assertThat(failed).doesNotContain("restarting it")
		assertThat(failed).contains("tap Quick Build")
	}

	private fun timeline(
		spans: E2eTimeline.HostSpans?,
		generation: Long = 5L,
	) = E2eTimeline(
		generation = generation,
		trigger = 0L,
		compileDone = 3_200L,
		deploySent = 5_500L,
		reloadLive = 6_000L,
		spans = spans,
	)

	@Test
	fun `a landed build reports where its time went`() {
		val line =
			quickBuildTimingLine(
				timeline(
					E2eTimeline.HostSpans(
						compileRpcMillis = 2_800L,
						dexRpcMillis = 400L,
						relinkRpcMillis = 2_300L,
					),
				),
			)

		assertThat(line)
			.isEqualTo(
				"Quick Build: generation 5 - compiled in 2.8s, dexed in 0.4s, " +
					"relinked in 2.3s, reloaded in 0.5s (6.0s from save to live).\n",
			)
	}

	@Test
	fun `the named phases add up to the total, with the remainder named`() {
		// Naming only the daemon spans leaves seconds of the loop unaccounted for, so the
		// line invites the reader to hunt for the difference. Every measured phase is
		// named, and whatever none of them measured is printed as
		// "other" - 1.9 + 0.3 + 1.8 + 0.2 + 0.1 + 0.5 + 1.2 = 6.0s, the total on the line.
		val line =
			quickBuildTimingLine(
				timeline(
					E2eTimeline.HostSpans(
						queueMillis = 1_900L,
						scanMillis = 300L,
						compileRpcMillis = 1_800L,
						policyMillis = 200L,
						dexRpcMillis = 100L,
					),
				),
			)

		assertThat(line)
			.isEqualTo(
				"Quick Build: generation 5 - queued for 1.9s, scanned in 0.3s, compiled in 1.8s, " +
					"checked classes in 0.2s, dexed in 0.1s, reloaded in 0.5s, other 1.2s " +
					"(6.0s from save to live).\n",
			)
	}

	@Test
	fun `a wait behind another build is named rather than buried in the total`() {
		// A save that queued behind an in-flight build can be the largest phase of a warm
		// edit, and it is not build cost - naming it is what stops a reader charging it to
		// the compiler.
		val line =
			quickBuildTimingLine(
				timeline(E2eTimeline.HostSpans(queueMillis = 1_950L, compileRpcMillis = 1_800L)),
			)!!

		assertThat(line).startsWith("Quick Build: generation 5 - queued for 2.0s, compiled in 1.8s")
	}

	@Test
	fun `a phase too small to render is folded into the remainder, not printed as zero`() {
		val line =
			quickBuildTimingLine(
				timeline(
					E2eTimeline.HostSpans(queueMillis = 10L, scanMillis = 20L, compileRpcMillis = 1_000L),
				),
			)!!

		assertThat(line).doesNotContain("queued")
		assertThat(line).doesNotContain("scanned")
		// The 30 ms still lands somewhere - inside "other", never silently dropped.
		assertThat(line).contains("other 4.5s")
	}

	@Test
	fun `a stage that did not run is not named`() {
		// A code-only edit never relinks resources; a zero would read as a stage that ran
		// instantly rather than one that was skipped.
		val line =
			quickBuildTimingLine(
				timeline(E2eTimeline.HostSpans(compileRpcMillis = 1_000L, dexRpcMillis = 240L)),
			)

		assertThat(line).contains("compiled in 1.0s, dexed in 0.2s")
		assertThat(line).doesNotContain("relinked")
	}

	@Test
	fun `a build that measured no stage says nothing`() {
		// A pre-timing daemon reports no spans at all; the loop still ran, so the status
		// line's own "reloaded to generation N" is the whole story and a bare total would
		// only repeat it.
		assertThat(quickBuildTimingLine(timeline(spans = null))).isNull()
		// A 40 ms scan is the only span measured and renders as 0.0s: nothing of the build was
		// measured, so a bare total plus a remainder would only restate the status line.
		assertThat(quickBuildTimingLine(timeline(E2eTimeline.HostSpans(scanMillis = 40L)))).isNull()
	}

	@Test
	fun `stopping the session is narrated, starting from nothing is not`() {
		assertThat(
			lines(QuickBuildStatus.UpToDate(4L, buildDurationMillis = null), QuickBuildStatus.Hidden())
				.joinToString(""),
		).contains("session stopped")
		assertThat(lines(null, QuickBuildStatus.Hidden())).isEmpty()
	}

	@Test
	fun `a failed start names the retry gesture and its save-clear narrates nothing`() {
		// The Gradle cause was already quoted by the proxy-app failure narration; this line
		// adds the gesture, since the flash naming it is transient (Q8).
		assertThat(
			lines(QuickBuildStatus.Provisioning(), QuickBuildStatus.Hidden(lastStartFailed = true))
				.joinToString(""),
		).contains("could not start - tap Quick Build to retry")
		// The save that clears the tone is a Hidden -> Hidden hop; "session stopped." there
		// would invent a session that never existed.
		assertThat(
			lines(QuickBuildStatus.Hidden(lastStartFailed = true), QuickBuildStatus.Hidden()),
		).isEmpty()
	}

	@Test
	fun `a rebaseline is not called the initial build`() {
		// The status is the one the session really emits for a rebaseline, taken from the reducer
		// rather than hand-written, and the previous status is the one the pane really holds. An
		// hand-written NeedsFullBuild paired with Provisioning would pass here while the device
		// still read "initial full build": the pane collects a conflating StateFlow off the
		// session thread, so the NeedsFullBuild hop is routinely never delivered and the
		// previous status is still the pre-save one.
		val text = lines(QuickBuildStatus.UpToDate(4L, buildDurationMillis = null), rebaselining()).joinToString("")

		assertThat(text).contains("rebuilding your app")
		assertThat(text).contains("a Gradle build file changed")
		assertThat(text).doesNotContain("initial")
	}

	/**
	 * The status a rebaseline really reaches, produced by the reducer and the status mapping that
	 * run in production rather than assumed.
	 *
	 * @return the status for a session whose gradle-file save has started its full rebuild.
	 */
	private fun rebaselining(): QuickBuildStatus {
		val invalidated = QuickBuildSessionState.Invalidated(InvalidationReason.GRADLE_CONFIG_CHANGED, 4L)
		val started = SessionReducer().reduce(invalidated, SessionEvent.ProxyAppRebuildStarted).state
		return QuickBuildStatus.from(started)
	}

	@Test
	fun `a session's first build is still called the initial build`() {
		assertThat(lines(QuickBuildStatus.Hidden(), QuickBuildStatus.Provisioning()).joinToString(""))
			.contains("running the initial full build")
	}

	@Test
	fun `a restarted session is not called the initial build`() {
		// T15: the restart was silent, so the pane is the one place a user could confirm it
		// happened at all - and it read "running the initial full build" on a session that had
		// been live for an hour. Status derived through the real reducer, like the rebaseline
		// case above, so the test cannot pass on a transition production never produces.
		val text = lines(QuickBuildStatus.UpToDate(4L, buildDurationMillis = null), restarting()).joinToString("")

		assertThat(text).contains("session restarted")
		assertThat(text).doesNotContain("initial")
	}

	@Test
	fun `a restart from a failed session is announced as a restart`() {
		// The state the escape hatch is actually reached from: three notices name Restart session
		// as the remedy, and every one of them fires on a failure.
		val failed =
			QuickBuildStatus.Failed(4L, SessionFailure.ProxyAppCrash("NullPointerException"))

		assertThat(lines(failed, restarting()).joinToString("")).contains("session restarted")
	}

	/**
	 * The status a user-requested restart really reaches, produced by the reducer and the status
	 * mapping that run in production rather than assumed.
	 *
	 * @return the status for a live session the user has just restarted.
	 */
	private fun restarting(): QuickBuildStatus {
		val live = QuickBuildSessionState.Ready(4L)
		val restarted =
			SessionReducer().reduce(live, SessionEvent.SessionRestartAndReprovisionRequested).state
		return QuickBuildStatus.from(restarted)
	}

	@Test
	fun `a failed proxy app build quotes Gradle's own reason`() {
		val text = quickBuildProxyAppFailureLines(GRADLE_FAILURE).joinToString("")

		// The whole point: the cause the user can act on, which lives nowhere else.
		assertThat(text).contains("Failed to find target with hash string 'android-37'")
		assertThat(text).contains("the full Gradle build failed")
	}

	@Test
	fun `a failed proxy app build quotes from the failure banner, not the progress before it`() {
		val text = quickBuildProxyAppFailureLines(GRADLE_FAILURE).joinToString("")

		assertThat(text).doesNotContain("Configure project")
		assertThat(text).doesNotContain("Task :app:preBuild")
	}

	@Test
	fun `a failure with nothing captured says so rather than pretending`() {
		val text = quickBuildProxyAppFailureLines(emptyList()).joinToString("")

		// A failure with no captured output must still say something; an honest line beats
		// an empty pane.
		assertThat(text).contains("Gradle reported no output")
	}

	@Test
	fun `only the newest failure banner is quoted`() {
		val twoBuilds = listOf("FAILURE: Build failed", "> stale cause") + GRADLE_FAILURE

		val text = quickBuildProxyAppFailureLines(twoBuilds).joinToString("")

		assertThat(text).doesNotContain("stale cause")
		assertThat(text).contains("android-37")
	}

	@Test
	fun `compiler errors are quoted when Gradle printed no failure banner`() {
		val output = listOf("> Task :app:compileDebugKotlin", "Foo.kt:12:5: error: unresolved reference")

		val text = quickBuildProxyAppFailureLines(output).joinToString("")

		assertThat(text).contains("error: unresolved reference")
	}

	@Test
	fun `the one-line summary is Gradle's cause, not the banner`() {
		val summary = quickBuildProxyAppFailureSummary(GRADLE_FAILURE)

		assertThat(summary).isEqualTo(
			"Failed to find target with hash string 'android-37' in: /sdk",
		)
	}

	@Test
	fun `the summary is null when there is no cause to quote, leaving the generic wording`() {
		assertThat(quickBuildProxyAppFailureSummary(emptyList())).isNull()
		assertThat(quickBuildProxyAppFailureSummary(listOf("> Task :app:preBuild"))).isNull()
	}

	@Test
	fun `a very long cause is truncated to fit a flashbar`() {
		val output =
			listOf("FAILURE: Build failed with an exception.", "> " + "x".repeat(400))

		val summary = quickBuildProxyAppFailureSummary(output)

		assertThat(summary!!.length).isAtMost(160)
		assertThat(summary).endsWith("…")
	}

	@Test
	fun `a running task is reported as progress`() {
		assertThat(quickBuildProxyAppProgressLine("> Task :app:compileV8DebugKotlin"))
			.isEqualTo("Quick Build:   :app:compileV8DebugKotlin\n")
	}

	@Test
	fun `tasks that did no work are dropped - they bury the ones that ran`() {
		assertThat(quickBuildProxyAppProgressLine("> Task :app:preBuild UP-TO-DATE")).isNull()
		assertThat(quickBuildProxyAppProgressLine("> Task :app:generateAssets FROM-CACHE")).isNull()
		assertThat(quickBuildProxyAppProgressLine("> Task :app:compileJava NO-SOURCE")).isNull()
		assertThat(quickBuildProxyAppProgressLine("> Task :app:lint SKIPPED")).isNull()
	}

	@Test
	fun `configuration and download chatter is dropped`() {
		// Nothing here is actionable, and at one line per dependency it would drown the tasks.
		assertThat(quickBuildProxyAppProgressLine("> Configure project :app")).isNull()
		assertThat(quickBuildProxyAppProgressLine("Download https://example/foo.jar")).isNull()
		assertThat(quickBuildProxyAppProgressLine("")).isNull()
		assertThat(quickBuildProxyAppProgressLine("   ")).isNull()
	}

	@Test
	fun `a task line with no task name is dropped rather than reported empty`() {
		assertThat(quickBuildProxyAppProgressLine("> Task")).isNull()
		assertThat(quickBuildProxyAppProgressLine("> Task   ")).isNull()
	}

	@Test
	fun `progress reporting does not swallow a failing task`() {
		// A task that FAILED did work and is the most important line in the build.
		assertThat(quickBuildProxyAppProgressLine("> Task :app:compileV8DebugKotlin FAILED"))
			.isEqualTo("Quick Build:   :app:compileV8DebugKotlin FAILED\n")
	}

	private companion object {
		/** A real Gradle configure failure, in the shape the capture buffer sees it. */
		private val GRADLE_FAILURE =
			listOf(
				"> Configure project :app",
				"> Task :app:preBuild UP-TO-DATE",
				"FAILURE: Build failed with an exception.",
				"* What went wrong:",
				"A problem occurred configuring project ':app'.",
				"> Failed to find target with hash string 'android-37' in: /sdk",
			)
	}
}
