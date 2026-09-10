package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.junit.Test

/**
 * The property this class exists for: a build narrates into the Build Output pane whether or not
 * an editor activity is on screen.
 *
 * The gap these tests simulate (ADFA-4128): narration collected inside
 * `repeatOnLifecycle(STARTED)` is cancelled whenever CoGo is backgrounded, so a build the user
 * left the editor to watch writes into a dead collector and the pane comes back holding the
 * newest generation only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickBuildOutputNarratorTest {
	private val statuses = MutableSharedFlow<QuickBuildStatus>(extraBufferCapacity = 64)
	private val written = mutableListOf<String>()
	private val sink: (String) -> Unit = { written += it }

	/**
	 * Runs [body] against an attached narrator whose scope dispatches eagerly, so an emission is
	 * delivered by the time the next line of the test runs.
	 */
	private fun narrating(body: suspend (QuickBuildOutputNarrator) -> Unit) =
		runTest {
			val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
			val narrator = QuickBuildOutputNarrator(scope)
			narrator.attach(statuses)
			try {
				body(narrator)
			} finally {
				scope.cancel()
			}
		}

	/**
	 * One progress line of a proxy app build the project on screen started - a handle taken now,
	 * as the provisioner takes one when it starts a build.
	 */
	private fun QuickBuildOutputNarrator.narrateProxyAppProgress(line: String) = proxyAppBuildNarration().progress(line)

	/** [narrateProxyAppProgress]'s counterpart for that build failing. */
	private fun QuickBuildOutputNarrator.narrateProxyAppBuildFailure(output: List<String>) = proxyAppBuildNarration().failure(output)

	/** One session's worth of transitions: provision, then two builds landing. */
	private suspend fun runTwoBuilds() {
		statuses.emit(QuickBuildStatus.Hidden())
		statuses.emit(QuickBuildStatus.Provisioning())
		statuses.emit(QuickBuildStatus.UpToDate(1L, buildDurationMillis = null))
		statuses.emit(QuickBuildStatus.Building(1L))
		statuses.emit(QuickBuildStatus.UpToDate(2L, buildDurationMillis = 500L))
		statuses.emit(QuickBuildStatus.Building(2L))
		statuses.emit(QuickBuildStatus.UpToDate(3L, buildDurationMillis = 600L))
	}

	private fun timeline(generation: Long) =
		E2eTimeline(
			generation = generation,
			trigger = 0L,
			compileDone = 3_000L,
			deploySent = 3_100L,
			reloadLive = 4_000L,
			spans = E2eTimeline.HostSpans(compileRpcMillis = 2_800L, dexRpcMillis = 400L),
		)

	@Test
	fun `builds narrated with no pane bound are kept, not lost`() =
		narrating { narrator ->
			runTwoBuilds()
			assertThat(written).isEmpty()

			narrator.bind(sink)

			// Every generation, in order - the whole point. The old lifecycle-scoped
			// collector delivered generation 3 alone, and only as an unnarratable replay.
			val pane = written.joinToString("")
			assertThat(pane).contains("session ready, running generation 1")
			assertThat(pane).contains("generation 2 in 0.5s")
			assertThat(pane).contains("generation 3 in 0.6s")
			assertThat(written.indexOfFirst { it.contains("generation 2") })
				.isLessThan(written.indexOfFirst { it.contains("generation 3") })
		}

	@Test
	fun `a bound pane sees each line as it happens`() =
		narrating { narrator ->
			narrator.bind(sink)
			runTwoBuilds()

			assertThat(written.joinToString("")).contains("generation 3 in 0.6s")
			// Nothing was held back for a later flush.
			narrator.bind(sink)
			assertThat(written.count { it.contains("generation 3") }).isEqualTo(1)
		}

	@Test
	fun `lines produced between two panes reach the second one`() =
		narrating { narrator ->
			narrator.bind(sink)
			statuses.emit(QuickBuildStatus.Hidden())
			statuses.emit(QuickBuildStatus.Provisioning())
			narrator.unbind(sink)

			// The activity is being recreated; a build lands in the gap.
			statuses.emit(QuickBuildStatus.UpToDate(1L, buildDurationMillis = null))
			statuses.emit(QuickBuildStatus.Building(1L))
			statuses.emit(QuickBuildStatus.UpToDate(2L, buildDurationMillis = 500L))
			assertThat(written.joinToString("")).doesNotContain("generation 2")

			val second = mutableListOf<String>()
			narrator.bind { second += it }
			assertThat(second.joinToString("")).contains("generation 2 in 0.5s")
		}

	@Test
	fun `a destroyed activity unbinding does not silence the pane that replaced it`() =
		narrating { narrator ->
			val stale: (String) -> Unit = { written += it }
			narrator.bind(stale)
			narrator.bind(sink)
			// Arrives after the new pane bound, as onDestroy does when it races onCreate.
			narrator.unbind(stale)

			statuses.emit(QuickBuildStatus.Hidden())
			statuses.emit(QuickBuildStatus.Provisioning())
			assertThat(written).isNotEmpty()
		}

	@Test
	fun `stage timings reach the pane`() =
		narrating { narrator ->
			narrator.bind(sink)
			narrator.narrate(timeline(generation = 2L))

			assertThat(written.joinToString("")).contains("generation 2 - compiled in 2.8s")
		}

	@Test
	fun `a loop with no measured stage narrates nothing`() =
		narrating { narrator ->
			narrator.bind(sink)
			// A pre-instrumentation daemon reports no span. A timing line with no timing in
			// it is worse than none, so nothing is written - and nothing queues either.
			narrator.narrate(timeline(generation = 2L).copy(spans = null))

			assertThat(written).isEmpty()
		}

	@Test
	fun `a proxy app task line reaches a bound pane`() =
		narrating { narrator ->
			narrator.bind(sink)
			narrator.narrateProxyAppProgress("> Task :app:compileV8DebugKotlin")

			assertThat(written.single()).contains(":app:compileV8DebugKotlin")
		}

	@Test
	fun `proxy app progress produced with no pane bound is kept, not lost`() =
		narrating { narrator ->
			// The 80s+ proxy app build is exactly when the user leaves the editor, so its
			// progress has to queue like every other line.
			narrator.narrateProxyAppProgress("> Task :app:mergeV8DebugResources")
			assertThat(written).isEmpty()

			narrator.bind(sink)
			assertThat(written.single()).contains(":app:mergeV8DebugResources")
		}

	@Test
	fun `a proxy app line not worth reporting is dropped, not queued`() =
		narrating { narrator ->
			narrator.narrateProxyAppProgress("Configure project :app")
			narrator.narrateProxyAppProgress("> Task :app:preBuild UP-TO-DATE")

			// Filtered before the queue, not just before the pane: otherwise a build's
			// chatter would flush into the next pane that binds.
			narrator.bind(sink)
			assertThat(written).isEmpty()
		}

	@Test
	fun `a failed proxy app build quotes Gradle's own output, header first`() =
		narrating { narrator ->
			narrator.bind(sink)
			narrator.narrateProxyAppBuildFailure(
				listOf(
					"> Task :app:preBuild UP-TO-DATE",
					"FAILURE: Build failed with an exception.",
					"* What went wrong:",
					"> failed to find target with hash string 'android-37'",
				),
			)

			val pane = written.joinToString("")
			// The cause is the whole point: the tooling API's own failure is a bare enum, so
			// without this quote the pane says a build failed and never says why.
			assertThat(pane).contains("failed to find target with hash string 'android-37'")
			assertThat(written.first()).contains("the full Gradle build failed")
			assertThat(written.indexOfFirst { it.contains("What went wrong") })
				.isLessThan(written.indexOfFirst { it.contains("android-37") })
			// Progress above the failure banner belongs to the part that worked.
			assertThat(pane).doesNotContain("preBuild")
		}

	@Test
	fun `a failed proxy app build with nothing captured still says the build failed`() =
		narrating { narrator ->
			narrator.bind(sink)
			narrator.narrateProxyAppBuildFailure(emptyList())

			assertThat(written.single()).contains("Gradle reported no output to quote")
		}

	@Test
	fun `an absent pane cannot make the backlog grow without bound`() =
		narrating { narrator ->
			repeat(250) { narrator.narrate(timeline(generation = it.toLong())) }

			narrator.bind(sink)

			// Capped at 200, dropping the oldest: a session left running with the editor
			// closed must not accumulate a line per build forever.
			assertThat(written).hasSize(200)
			assertThat(written.first()).contains("generation 50 -")
			assertThat(written.last()).contains("generation 249 -")
		}

	@Test
	fun `reset drops queued lines, so a closed project's narration cannot flush into the next one`() =
		narrating { narrator ->
			// The narrator is a process-wide singleton and its queue outlives any one editor.
			// Lines written with no pane bound belong to the project that produced them, so
			// without a reset the NEXT project's Build Output opens holding the previous
			// project's progress - attributed to a build it never ran.
			narrator.narrateProxyAppProgress("> Task :app:mergeV8DebugResources")
			assertThat(written).isEmpty()

			narrator.reset()
			narrator.bind(sink)

			assertThat(written).isEmpty()

			// The pane still works afterwards; only the stale queue went away.
			narrator.narrateProxyAppProgress("> Task :app:compileV8DebugKotlin")
			assertThat(written.single()).contains(":app:compileV8DebugKotlin")
		}

	@Test
	fun `lines narrated after a reset with no pane are dropped, not queued for the next project`() =
		narrating { narrator ->
			// The session torn down alongside the reset narrates its own stop asynchronously,
			// after the reset ran: with only the queue cleared those lines queued again and
			// flushed into the next project's Build Output as that project's progress.
			narrator.reset()
			narrator.narrateProxyAppProgress("> Task :app:mergeV8DebugResources")

			narrator.bind(sink)

			assertThat(written).isEmpty()

			// Once a pane is bound the narration is live again, and the next unbound stretch
			// queues as before.
			narrator.narrateProxyAppProgress("> Task :app:compileV8DebugKotlin")
			assertThat(written.single()).contains(":app:compileV8DebugKotlin")
			narrator.unbind(sink)
			narrator.narrateProxyAppProgress("> Task :app:dexV8Debug")
			narrator.bind(sink)
			assertThat(written).hasSize(2)
			assertThat(written[1]).contains(":app:dexV8Debug")
		}

	@Test
	fun `the closing project's build cannot narrate into the next project's pane`() =
		narrating { narrator ->
			// The next project's activity binds its pane in onCreate, while the closing
			// project's proxy app build is still being cancelled - the cancel is
			// fire-and-forget and the progress listener keeps firing. Dropping only until the
			// next bind put those lines in the new project's Build Output.
			val closingBuild = narrator.proxyAppBuildNarration()
			val teardown = CompletableDeferred<Unit>()
			narrator.reset(untilQuiet = { teardown.await() })

			narrator.bind(sink)
			closingBuild.progress("> Task :app:mergeV8DebugResources")

			assertThat(written).isEmpty()

			// Still dropped once the teardown reports quiet, and dropped whole: it is the
			// build that belongs to the closed project, not the stretch of time. A cancelled
			// build reports a failure too, and that quote is the loudest of its late lines.
			teardown.complete(Unit)
			closingBuild.progress("> Task :app:compileV8DebugKotlin")
			closingBuild.failure(listOf("FAILURE: Build failed with an exception."))
			assertThat(written).isEmpty()
		}

	@Test
	fun `the opened project's own build narrates while the closed project's teardown drags on`() =
		narrating { narrator ->
			// The teardown being awaited is the closed project's, and it can take a daemon
			// shutdown and a scratch-tree removal to finish. Muting every line until then
			// muted the project the user is now looking at: its build narrated into nothing.
			narrator.reset(untilQuiet = { CompletableDeferred<Unit>().await() })
			narrator.bind(sink)

			val opened = narrator.proxyAppBuildNarration()
			opened.progress("> Task :app:compileV8DebugKotlin")

			assertThat(written.joinToString("")).contains(":app:compileV8DebugKotlin")
		}

	@Test
	fun `a tap in the opened project narrates while the closed project's teardown drags on`() =
		narrating { narrator ->
			narrator.reset(untilQuiet = { CompletableDeferred<Unit>().await() })
			narrator.bind(sink)

			// A session announcing its own full build is the closed project's cue to stop
			// being narrated for: this stream is ordered, so everything the closed session
			// had left to say arrived before it.
			statuses.emit(QuickBuildStatus.Hidden())
			statuses.emit(QuickBuildStatus.Provisioning())
			statuses.emit(QuickBuildStatus.UpToDate(1L, buildDurationMillis = null))

			val pane = written.joinToString("")
			assertThat(pane).contains("running the initial full build")
			assertThat(pane).contains("session ready, running generation 1")
		}

	@Test
	fun `the closed project's own session tail stays out of the next project's pane`() =
		narrating { narrator ->
			// The other half of the same rule: until the opened project says something of its
			// own, the status stream is still the closed session finishing its sentence.
			statuses.emit(QuickBuildStatus.UpToDate(2L, buildDurationMillis = 500L))
			narrator.reset(untilQuiet = { CompletableDeferred<Unit>().await() })
			narrator.bind(sink)

			statuses.emit(QuickBuildStatus.Hidden())

			assertThat(written).isEmpty()
		}

	@Test
	fun `a reset with no teardown to await still drops only until the next pane binds`() =
		narrating { narrator ->
			// The narrator is also reset when the feature has no session manager to ask, so
			// the awaitable is optional and its absence must not silence the pane.
			narrator.reset()
			narrator.bind(sink)
			narrator.narrateProxyAppProgress("> Task :app:compileV8DebugKotlin")

			assertThat(written.single()).contains(":app:compileV8DebugKotlin")
		}
}
