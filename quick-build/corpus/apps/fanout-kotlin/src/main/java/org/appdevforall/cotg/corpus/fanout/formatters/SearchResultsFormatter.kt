package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the search results count for display, via the shared inline fmt(). */
class SearchResultsFormatter {
	fun describe(count: Int): String = fmt("search results", count)
}
