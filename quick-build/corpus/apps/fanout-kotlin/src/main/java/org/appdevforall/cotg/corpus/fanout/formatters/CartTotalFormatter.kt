package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the cart items count for display, via the shared inline fmt(). */
class CartTotalFormatter {
	fun describe(count: Int): String = fmt("cart items", count)
}
