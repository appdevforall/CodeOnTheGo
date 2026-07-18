package org.appdevforall.cotg.corpus.multiactivity.core

class ResultCodeFormatter {

	fun describe(resultCode: Int): String =
		when (resultCode) {
			-1 -> "OK_QB_RESULT_MARKER_V2"
			0 -> "CANCELED"
			else -> "CODE_$resultCode"
		}
}
