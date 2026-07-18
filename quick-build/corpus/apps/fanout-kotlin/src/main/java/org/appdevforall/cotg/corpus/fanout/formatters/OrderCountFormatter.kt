package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the orders count for display, via the shared inline fmt(). */
class OrderCountFormatter {
	fun describe(count: Int): String = fmt("orders", count)
}
