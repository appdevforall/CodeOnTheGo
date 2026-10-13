package org.appdevforall.cotg.quickbuild.domain.telemetry

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
	fun `format carries the compile ordinal, so a duration can be placed on the warm-up curve`() {
		// Without it a 3 s cold build and a 0.9 s warm one read as variance rather than as two
		// ends of one curve - which is how three separate readings of this line went wrong.
		val second = sample.copy(counts = E2eTimeline.BuildCounts(compileOrdinal = 2))

		assertThat(second.format())
			.isEqualTo(
				"quickbuild-e2e: gen=7 trigger=1000 compileDone=1600 deploySent=1650 " +
					"reloadLive=1720 compileOrdinal=2",
			)
	}

	@Test
	fun `a route that ran no compile omits the ordinal rather than printing a zero`() {
		// A resources-only relink never reaches recordCompileSteps, so it has no ordinal. A `0`
		// there would parse as a real value and read as the coldest possible build.
		val relinkOnly = sample.copy(counts = E2eTimeline.BuildCounts(changedClasses = 0))

		assertThat(relinkOnly.counts?.compileOrdinal).isNull()
		assertThat(relinkOnly.format()).doesNotContain("compileOrdinal")
		assertThat(relinkOnly.format()).isEqualTo(sample.format())
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
	fun `the log line takes the ordinal and nothing else as the new fields arrive`() {
		// The harness greps this line. Spans, step timings, the other counts and the scratch
		// filesystem stay off it and travel through the structured sinks; only the ordinal
		// earned a place, because the stamps are unreadable without it.
		val rich =
			sample.copy(
				spans = E2eTimeline.HostSpans(scanMillis = 40),
				steps = E2eTimeline.StepTimings(kotlinMillis = 300),
				counts = E2eTimeline.BuildCounts(allSources = 292, classBytes = 4096, compileOrdinal = 41),
				scratchFsType = "fuse",
			)

		assertThat(rich.format())
			.isEqualTo(
				"quickbuild-e2e: gen=7 trigger=1000 compileDone=1600 deploySent=1650 " +
					"reloadLive=1720 compileOrdinal=41",
			)
	}

	@Test
	fun `the five stamps keep their order and lead the line, so an existing parser still matches`() {
		// The benchmark harness's parser is an unanchored search for the five stamps in this
		// order (harness/e2e_matrix_device.py, _LINE). Appending kept it matching; reordering
		// or inserting would not, and would fail silently rather than loudly.
		val line = sample.copy(counts = E2eTimeline.BuildCounts(compileOrdinal = 41)).format()
		val stamps =
			Regex(
				"gen=(-?\\d+)\\s+trigger=(-?\\d+)\\s+compileDone=(-?\\d+)\\s+" +
					"deploySent=(-?\\d+)\\s+reloadLive=(-?\\d+)",
			)

		val match = stamps.find(line)
		assertThat(match).isNotNull()
		assertThat(match!!.groupValues.drop(1))
			.containsExactly("7", "1000", "1600", "1650", "1720")
			.inOrder()
	}
}
