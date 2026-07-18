package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the notifications count for display, via the shared inline fmt(). */
class NotificationCountFormatter {
	fun describe(count: Int): String = fmt("notifications", count)
}
