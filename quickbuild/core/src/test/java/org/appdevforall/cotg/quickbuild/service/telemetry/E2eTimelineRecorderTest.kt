package org.appdevforall.cotg.quickbuild.service.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class E2eTimelineRecorderTest {
	@Test
	fun `route with no compile falls back to deploySent, not trigger`() {
		// E.g. a resources-only route: markCompileDone is never called, so per the
		// E2eTimeline contract t1 == t2 and compileMillis measures relink+package.
		val recorder = E2eTimelineRecorder(trigger = 1_000) { null }
		recorder.markDeploySent(1_500)
		val timeline = recorder.completed(generation = 3, reloadLive = 1_700)
		assertThat(timeline.compileDone).isEqualTo(1_500)
		assertThat(timeline.compileDone).isEqualTo(timeline.deploySent)
		assertThat(timeline.compileMillis).isEqualTo(500)
	}

	@Test
	fun `markCompileDone stamps t1 ahead of the deploy`() {
		val recorder = E2eTimelineRecorder(trigger = 1_000) { null }
		recorder.markCompileDone(1_400)
		recorder.markDeploySent(1_500)
		val timeline = recorder.completed(generation = 3, reloadLive = 1_700)
		assertThat(timeline.compileDone).isEqualTo(1_400)
	}

	@Test
	fun `empty step, span and count groups are absent, not zero-filled`() {
		val recorder = E2eTimelineRecorder(trigger = 1_000) { null }
		recorder.markDeploySent(1_500)
		val timeline = recorder.completed(generation = 3, reloadLive = 1_700)
		assertThat(timeline.steps).isNull()
		assertThat(timeline.spans).isNull()
		assertThat(timeline.counts).isNull()
	}

	@Test
	fun `recorded groups come through non-empty`() {
		val recorder = E2eTimelineRecorder(trigger = 1_000) { "ext4" }
		recorder.recordScan(20)
		recorder.recordRelinkSteps(aapt2CompileMillis = 80, aapt2LinkMillis = 40)
		val timeline = recorder.completed(generation = 3, reloadLive = 1_700)
		assertThat(timeline.spans?.scanMillis).isEqualTo(20)
		assertThat(timeline.steps?.aapt2CompileMillis).isEqualTo(80)
		assertThat(timeline.steps?.aapt2LinkMillis).isEqualTo(40)
		assertThat(timeline.scratchFsType).isEqualTo("ext4")
	}
}
