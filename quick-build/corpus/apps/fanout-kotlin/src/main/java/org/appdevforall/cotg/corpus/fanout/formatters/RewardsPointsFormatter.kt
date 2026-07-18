package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the reward points count for display, via the shared inline fmt(). */
class RewardsPointsFormatter {
	fun describe(count: Int): String = fmt("reward points", count)
}
