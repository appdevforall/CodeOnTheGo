package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * The capture is what a suppressed internal build's failure report quotes - if a line is
 * routed, bounded, or drained wrongly, a proxy-app build failure either leaks into the
 * editor's build UI or loses Gradle's reason entirely.
 */
class InternalBuildOutputCaptureTest {
	@Test
	fun `a line goes to the editor listener when it is not suppressed, and is not captured`() {
		val capture = InternalBuildOutputCapture(maxLines = 10)
		val editorListener = mockk<GradleBuildService.EventListener>(relaxed = true)

		capture.onLine("> Task :app:assembleDebug", editorListener, null)

		verify(exactly = 1) { editorListener.onOutput("> Task :app:assembleDebug") }
		assertThat(capture.drain()).isEmpty()
	}

	@Test
	fun `a suppressed line is captured and reported to the progress listener`() {
		val capture = InternalBuildOutputCapture(maxLines = 10)
		val reported = mutableListOf<String>()

		capture.onLine("FAILURE: Build failed", editorListener = null, progressListener = reported::add)

		assertThat(reported).containsExactly("FAILURE: Build failed")
		assertThat(capture.drain()).containsExactly("FAILURE: Build failed")
	}

	@Test
	fun `the tail is bounded - oldest lines are dropped first`() {
		val capture = InternalBuildOutputCapture(maxLines = 3)

		for (i in 1..5) {
			capture.onLine("line $i", editorListener = null, progressListener = null)
		}

		// Gradle puts the cause at the END of the stream, so the tail must keep the newest.
		assertThat(capture.drain()).containsExactly("line 3", "line 4", "line 5").inOrder()
	}

	@Test
	fun `drain clears - one failure's report can never quote the next build`() {
		val capture = InternalBuildOutputCapture(maxLines = 10)
		capture.onLine("stale reason", editorListener = null, progressListener = null)

		assertThat(capture.drain()).containsExactly("stale reason")
		assertThat(capture.drain()).isEmpty()
	}

	@Test
	fun `clear drops an unread tail`() {
		val capture = InternalBuildOutputCapture(maxLines = 10)
		capture.onLine("previous build's tail", editorListener = null, progressListener = null)

		capture.clear()

		assertThat(capture.drain()).isEmpty()
	}

	@Test
	fun `a throwing progress listener cannot veto the capture`() {
		val capture = InternalBuildOutputCapture(maxLines = 10)

		capture.onLine(
			"the one copy of the reason",
			editorListener = null,
			progressListener = { throw IllegalStateException("listener blew up") },
		)

		assertThat(capture.drain()).containsExactly("the one copy of the reason")
	}
}
