package org.appdevforall.cotg.corpus.multiactivity.core

class ResultCodeFormatter {
	fun describe(resultCode: Int): String =
		when (resultCode) {
			-1 -> "OK" // matches android.app.Activity.RESULT_OK
			0 -> "CANCELED" // matches android.app.Activity.RESULT_CANCELED
			else -> "CODE_$resultCode"
		}
}
