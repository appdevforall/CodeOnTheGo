package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the session minutes count for display, via the shared inline fmt(). */
class SessionDurationFormatter {
	fun describe(count: Int): String = fmt("session minutes", count)
}
