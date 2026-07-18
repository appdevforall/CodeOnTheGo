package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the favorites count for display, via the shared inline fmt(). */
class FavoritesCountFormatter {
	fun describe(count: Int): String = fmt("favorites", count)
}
