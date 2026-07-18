package org.appdevforall.cotg.corpus.medium.formatters

class DateFormatter {
	fun format(
		day: Int,
		month: Int,
		year: Int,
	): String {
		val paddedDay = day.toString().padStart(2, '0')
		val paddedMonth = month.toString().padStart(2, '0')
		return "$paddedDay/$paddedMonth/$year"
	}
}
