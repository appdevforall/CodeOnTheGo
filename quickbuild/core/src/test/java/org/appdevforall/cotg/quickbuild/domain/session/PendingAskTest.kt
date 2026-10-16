package org.appdevforall.cotg.quickbuild.domain.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the two behaviours the session's callers depend on and nothing else asserts: the age
 * [PendingAsk.answer] reports is measured from the user's *first* tap, and it is reported exactly
 * once per tap.
 *
 * The clock is injected, so no assertion here depends on wall-clock time elapsing.
 */
class PendingAskTest {
	private var now = 1_000L
	private val ask = PendingAsk { now }

	@Test
	fun `an answered tap reports how long the user waited`() {
		ask.record()
		assertThat(ask.recordedAtMillis).isEqualTo(1_000L)

		now = 1_750L

		assertThat(ask.answer()).isEqualTo(750L)
	}

	@Test
	fun `a second tap keeps the first stamp, so the age is the user's wait and not the last tap's`() {
		ask.record()

		now = 1_400L
		ask.record()
		now = 1_900L

		// 900 ms since the tap the user is still waiting on, not 500 ms since they tapped again.
		assertThat(ask.answer()).isEqualTo(900L)
	}

	@Test
	fun `answering with nothing outstanding returns null`() {
		assertThat(ask.isOutstanding).isFalse()

		assertThat(ask.answer()).isNull()
	}

	@Test
	fun `a withdrawn tap is no longer outstanding and cannot be answered`() {
		ask.record()

		ask.withdraw()
		now = 9_000L

		assertThat(ask.isOutstanding).isFalse()
		assertThat(ask.answer()).isNull()
	}

	@Test
	fun `answering settles the tap, so a second landing does not answer it again`() {
		ask.record()
		now = 1_200L

		assertThat(ask.answer()).isEqualTo(200L)
		// QuickBuildSessionManager answers in two places - the rebuild's own relaunch and the
		// switch the landing asks for. The second must find nothing left to answer.
		assertThat(ask.answer()).isNull()
		assertThat(ask.isOutstanding).isFalse()
	}

	@Test
	fun `a tap recorded after an answer is timed from the new tap`() {
		ask.record()
		now = 1_500L
		ask.answer()

		now = 5_000L
		ask.record()
		now = 5_200L

		assertThat(ask.answer()).isEqualTo(200L)
	}

	@Test
	fun `isOutstanding follows the tap the orchestrator reads it for`() {
		assertThat(ask.isOutstanding).isFalse()

		ask.record()
		assertThat(ask.isOutstanding).isTrue()

		ask.answer()
		assertThat(ask.isOutstanding).isFalse()
	}
}
