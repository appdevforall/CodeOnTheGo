package com.itsaky.androidide.testing

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitProgressFormatterTest {

	@Test
	fun formatMinutesElapsedMessage_usesMinuteUnits() {
		val msg = WaitProgressFormatter.formatMinutesElapsedMessage("project init", elapsedMinutes = 3, maxMinutes = 5)
		assertEquals("waiting for project init (5m max): 3m elapsed so far", msg)
	}

	@Test
	fun formatMinutesElapsedMessage_changesElapsedValue() {
		val msg = WaitProgressFormatter.formatMinutesElapsedMessage("project init", elapsedMinutes = 4, maxMinutes = 5)
		assertEquals("waiting for project init (5m max): 4m elapsed so far", msg)
	}
}
