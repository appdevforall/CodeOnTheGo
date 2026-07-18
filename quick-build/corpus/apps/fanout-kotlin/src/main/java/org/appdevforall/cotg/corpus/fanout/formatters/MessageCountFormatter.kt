package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the messages count for display, via the shared inline fmt(). */
class MessageCountFormatter {
	fun describe(count: Int): String = fmt("messages", count)
}
