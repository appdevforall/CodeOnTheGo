package org.appdevforall.cotg.corpus.fanout.formatters

import org.appdevforall.cotg.corpus.fanout.core.fmt

/** Formats the referrals count for display, via the shared inline fmt(). */
class ReferralCountFormatter {
	fun describe(count: Int): String = fmt("referrals", count)
}
