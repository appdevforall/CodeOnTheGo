package com.itsaky.androidide.testing

object WaitProgressFormatter {
	fun formatMinutesElapsedMessage(waitName: String, elapsedMinutes: Long, maxMinutes: Long): String =
		"waiting for $waitName (${maxMinutes}m max): ${elapsedMinutes}m elapsed so far"
}
