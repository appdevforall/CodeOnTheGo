package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class E2eTimelineTest {
	private val sample = E2eTimeline(generation = 7, trigger = 1000, compileDone = 1600, deploySent = 1650, reloadLive = 1720)

	@Test
	fun `format renders the grep-stable structured line`() {
		assertThat(sample.format())
			.isEqualTo("quickbuild-e2e: gen=7 trigger=1000 compileDone=1600 deploySent=1650 reloadLive=1720")
	}

	@Test
	fun `deltas decompose the loop into compile, stage and reload`() {
		assertThat(sample.compileMillis).isEqualTo(600)
		assertThat(sample.stageMillis).isEqualTo(50)
		assertThat(sample.reloadMillis).isEqualTo(70)
		assertThat(sample.totalMillis).isEqualTo(720)
		// The parts partition the whole - no gaps, no double-count.
		assertThat(sample.compileMillis + sample.stageMillis + sample.reloadMillis)
			.isEqualTo(sample.totalMillis)
	}

	@Test
	fun `format then parse round-trips`() {
		assertThat(E2eTimeline.parse(sample.format())).isEqualTo(sample)
	}

	@Test
	fun `parse tolerates a logcat prefix around the line`() {
		val logcatLine =
			"07-22 23:14:05.123  4821  4890 I QuickBuildExecutorImpl: " +
				"quickbuild-e2e: gen=3 trigger=42 compileDone=9042 deploySent=9050 reloadLive=9120"
		assertThat(E2eTimeline.parse(logcatLine))
			.isEqualTo(E2eTimeline(3, 42, 9042, 9050, 9120))
	}

	@Test
	fun `parse handles negative deltas without special-casing`() {
		// elapsedRealtime never goes backward, but a hand-authored fixture might; parse must
		// stay total and let the consumer decide the reading is degenerate.
		val line = "quickbuild-e2e: gen=1 trigger=100 compileDone=90 deploySent=90 reloadLive=200"
		val parsed = E2eTimeline.parse(line)!!
		assertThat(parsed.compileMillis).isEqualTo(-10)
		assertThat(parsed.totalMillis).isEqualTo(100)
	}

	@Test
	fun `parse returns null when the tag is absent`() {
		assertThat(E2eTimeline.parse("gen=1 trigger=1 compileDone=2 deploySent=3 reloadLive=4")).isNull()
	}

	@Test
	fun `parse returns null when a field is missing`() {
		assertThat(E2eTimeline.parse("quickbuild-e2e: gen=1 trigger=2 compileDone=3 deploySent=4")).isNull()
	}

	@Test
	fun `parse returns null on an unrelated line`() {
		assertThat(E2eTimeline.parse("I QuickBuildSessionManager: Proxy app connected at generation 4")).isNull()
	}

	@Test
	fun `a build whose spans cover every step leaves no residual`() {
		// The healthy shape, and the one the sora-editor-full device rows showed: the host
		// spans partition [trigger, deploySent] and reload covers the rest.
		// 40 + 500 + 30 + 80 = 650 = deploySent - trigger; reload = 70.
		val timeline =
			sample.copy(
				spans =
					E2eTimeline.HostSpans(
						scanMillis = 40,
						compileRpcMillis = 500,
						policyMillis = 30,
						dexRpcMillis = 80,
					),
			)

		assertThat(timeline.accountedMillis).isEqualTo(720)
		assertThat(timeline.unaccountedMillis).isEqualTo(0)
	}

	@Test
	fun `an untimed step shows up as residual rather than inflating a measured span`() {
		// The regression this field exists to catch: something inside the build takes 200 ms
		// and nothing measures it. Every named span keeps its own honest value; the gap is
		// what grows.
		val timeline =
			sample.copy(
				spans =
					E2eTimeline.HostSpans(
						scanMillis = 40,
						compileRpcMillis = 300,
						policyMillis = 30,
						dexRpcMillis = 80,
					),
			)

		assertThat(timeline.unaccountedMillis).isEqualTo(200)
		assertThat(timeline.accountedMillis).isEqualTo(520)
	}

	@Test
	fun `a relink route accounts through the relink span, not through stage`() {
		// A resources-only build never marks compileDone, so its relink lands in
		// compileMillis rather than stageMillis. The accounting must not care which side of
		// that boundary the work fell on - only that a span measured it.
		val resourcesOnly =
			E2eTimeline(
				generation = 8,
				trigger = 1_000,
				compileDone = 1_650,
				deploySent = 1_650,
				reloadLive = 1_720,
				spans = E2eTimeline.HostSpans(relinkRpcMillis = 650),
			)

		assertThat(resourcesOnly.stageMillis).isEqualTo(0)
		assertThat(resourcesOnly.compileMillis).isEqualTo(650)
		assertThat(resourcesOnly.unaccountedMillis).isEqualTo(0)
	}

	@Test
	fun `daemon-internal step timings never count toward the accounted total`() {
		// kotlin/javac/strip/d8 and the snapshot phases run INSIDE the compile and dex RPCs.
		// Adding them would double-count and drive the residual negative, hiding a real gap.
		val timeline =
			sample.copy(
				spans =
					E2eTimeline.HostSpans(
						scanMillis = 40,
						compileRpcMillis = 500,
						policyMillis = 30,
						dexRpcMillis = 80,
					),
				steps =
					E2eTimeline.StepTimings(
						kotlinMillis = 300,
						javaMillis = 100,
						stripMillis = 40,
						d8Millis = 35,
						preSnapMillis = 20,
						postSnapMillis = 25,
						javaAbiSnapMillis = 50,
					),
			)

		assertThat(timeline.accountedMillis).isEqualTo(720)
		assertThat(timeline.unaccountedMillis).isEqualTo(0)
	}

	@Test
	fun `no measured spans claims no residual`() {
		// A pre-instrumentation daemon measures nothing. Reporting the whole build as
		// "unaccounted" would be a false alarm, not an honest gap.
		assertThat(sample.spans).isNull()
		assertThat(sample.unaccountedMillis).isEqualTo(0)
		assertThat(sample.accountedMillis).isEqualTo(70)
	}

	@Test
	fun `walkMillis sums the two output-tree snapshots and stays null when neither ran`() {
		assertThat(E2eTimeline.StepTimings(preSnapMillis = 120, postSnapMillis = 130).walkMillis)
			.isEqualTo(250)
		assertThat(E2eTimeline.StepTimings(preSnapMillis = 120).walkMillis).isEqualTo(120)
		assertThat(E2eTimeline.StepTimings(kotlinMillis = 5).walkMillis).isNull()
	}

	@Test
	fun `the new groups are absent-aware so an unreported group stays null`() {
		assertThat(E2eTimeline.HostSpans().isEmpty()).isTrue()
		assertThat(E2eTimeline.HostSpans(scanMillis = 1).isEmpty()).isFalse()
		assertThat(E2eTimeline.BuildCounts().isEmpty()).isTrue()
		assertThat(E2eTimeline.BuildCounts(compileOrdinal = 1).isEmpty()).isFalse()
		assertThat(E2eTimeline.StepTimings().isEmpty()).isTrue()
		assertThat(E2eTimeline.StepTimings(javaAbiSnapMillis = 1).isEmpty()).isFalse()
	}

	@Test
	fun `the five-stamp log line stays frozen as the new fields arrive`() {
		// The harness greps this line; adding telemetry must not change it.
		val rich =
			sample.copy(
				spans = E2eTimeline.HostSpans(scanMillis = 40),
				counts = E2eTimeline.BuildCounts(allSources = 292, compileOrdinal = 2),
				scratchFsType = "fuse",
			)
		assertThat(rich.format()).isEqualTo(sample.format())
	}
}
