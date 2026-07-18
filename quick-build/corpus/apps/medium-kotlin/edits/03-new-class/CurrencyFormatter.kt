package org.appdevforall.cotg.corpus.medium.formatters

/** New in edit 03: formats a price in cents as a dollar string. */
class CurrencyFormatter {
	fun format(priceCents: Int): String {
		val dollars = priceCents / 100
		val cents = (priceCents % 100).toString().padStart(2, '0')
		return "QB_MARKER_STEP3:$$dollars.$cents"
	}
}
