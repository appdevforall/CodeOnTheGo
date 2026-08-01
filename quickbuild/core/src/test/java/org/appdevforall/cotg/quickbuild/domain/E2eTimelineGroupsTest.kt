package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The absent-awareness contract of the three timing groups: a group with ANY reported
 * field is not empty (the metrics sink keys emission on `isEmpty`), and the walk sum
 * treats a half-reported pair as measured. One field at a time, so a regression that
 * drops a single field from the emptiness check fails a named case.
 */
class E2eTimelineGroupsTest {
	@Test
	fun `an all-null StepTimings is empty`() {
		assertThat(E2eTimeline.StepTimings().isEmpty()).isTrue()
	}

	@Test
	fun `each StepTimings field alone makes the group non-empty`() {
		val singles =
			listOf(
				E2eTimeline.StepTimings(kotlinMillis = 1),
				E2eTimeline.StepTimings(javaMillis = 1),
				E2eTimeline.StepTimings(stripMillis = 1),
				E2eTimeline.StepTimings(d8Millis = 1),
				E2eTimeline.StepTimings(aapt2CompileMillis = 1),
				E2eTimeline.StepTimings(aapt2LinkMillis = 1),
				E2eTimeline.StepTimings(preSnapMillis = 1),
				E2eTimeline.StepTimings(postSnapMillis = 1),
				E2eTimeline.StepTimings(javaAbiSnapMillis = 1),
			)

		singles.forEach { timings ->
			assertThat(timings.isEmpty()).isFalse()
		}
	}

	@Test
	fun `walkMillis counts a lone pre-compile snapshot`() {
		assertThat(E2eTimeline.StepTimings(preSnapMillis = 120).walkMillis).isEqualTo(120)
	}

	@Test
	fun `walkMillis counts a lone post-compile snapshot`() {
		assertThat(E2eTimeline.StepTimings(postSnapMillis = 130).walkMillis).isEqualTo(130)
	}

	@Test
	fun `an all-null HostSpans is empty with a zero total`() {
		val spans = E2eTimeline.HostSpans()

		assertThat(spans.isEmpty()).isTrue()
		assertThat(spans.totalMillis).isEqualTo(0)
	}

	@Test
	fun `each HostSpans field alone makes the group non-empty and counts toward the total`() {
		val singles =
			listOf(
				E2eTimeline.HostSpans(scanMillis = 7),
				E2eTimeline.HostSpans(compileRpcMillis = 7),
				E2eTimeline.HostSpans(policyMillis = 7),
				E2eTimeline.HostSpans(dexRpcMillis = 7),
				E2eTimeline.HostSpans(relinkRpcMillis = 7),
			)

		singles.forEach { spans ->
			assertThat(spans.isEmpty()).isFalse()
			assertThat(spans.totalMillis).isEqualTo(7)
		}
	}

	@Test
	fun `an all-null BuildCounts is empty`() {
		assertThat(E2eTimeline.BuildCounts().isEmpty()).isTrue()
	}

	@Test
	fun `each BuildCounts field alone makes the group non-empty`() {
		val singles =
			listOf(
				E2eTimeline.BuildCounts(allSources = 1),
				E2eTimeline.BuildCounts(kotlinCompiled = 1),
				E2eTimeline.BuildCounts(javaSources = 1),
				E2eTimeline.BuildCounts(changedClasses = 1),
				E2eTimeline.BuildCounts(classFiles = 1),
				E2eTimeline.BuildCounts(classBytes = 1L),
				E2eTimeline.BuildCounts(compileOrdinal = 1L),
			)

		singles.forEach { counts ->
			assertThat(counts.isEmpty()).isFalse()
		}
	}
}
