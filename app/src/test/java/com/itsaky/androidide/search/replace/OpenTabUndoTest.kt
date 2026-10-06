package com.itsaky.androidide.search.replace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenTabUndoTest {
	@Test
	fun restoresWhenBufferStillHoldsReplacedText() {
		assertThat(OpenTabUndo.decide("after", "after")).isEqualTo(OpenTabUndo.Decision.RESTORE)
	}

	@Test
	fun refusesWhenBufferChangedAfterReplace() {
		assertThat(OpenTabUndo.decide("after plus typing", "after")).isEqualTo(OpenTabUndo.Decision.CHANGED)
	}

	@Test
	fun reportsClosedTab() {
		assertThat(OpenTabUndo.decide(null, "after")).isEqualTo(OpenTabUndo.Decision.CLOSED)
	}
}
