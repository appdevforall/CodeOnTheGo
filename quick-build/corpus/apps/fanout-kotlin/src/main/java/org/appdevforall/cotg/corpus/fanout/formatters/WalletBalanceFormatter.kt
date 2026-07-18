package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the wallet balance count for display, via the shared inline fmt(). */
class WalletBalanceFormatter {
	fun describe(count: Int): String = fmt("wallet balance", count)
}
