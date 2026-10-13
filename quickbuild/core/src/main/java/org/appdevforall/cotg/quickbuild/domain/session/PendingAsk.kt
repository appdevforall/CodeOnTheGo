package org.appdevforall.cotg.quickbuild.domain.session

/**
 * The one record of a Quick Build tap that still owes the user the switch to the proxy app.
 *
 * A tap means "show me the app once my changes are in it". Between the tap and that switch the
 * session can provision, rebaseline, park, chain one Gradle build into another, or be stopped,
 * and every one of those used to keep its own copy of the ask: two reducer states, two
 * orchestrator flags, a per-build snapshot and a timestamp in the manager. Each stop, park and
 * restart then had to clear every copy by hand, and each review round found the one it missed.
 * This class is the single copy. The reducer says when it is recorded and withdrawn (through
 * [SessionEffect.RecordAsk] and [SessionEffect.WithdrawAsk]), the manager answers it when the
 * proxy app is actually brought forward, and everything else only reads [isOutstanding].
 *
 * Written on the session dispatcher only; read from the orchestrator's own threads, hence the
 * volatile stamp.
 *
 * @param nowMillis the clock the age reported by [answer] is measured on.
 */
class PendingAsk(
	private val nowMillis: () -> Long,
) {
	/** When the outstanding tap landed, null when nothing is owed. */
	@Volatile
	var recordedAtMillis: Long? = null
		private set

	/** Whether a tap is still waiting to be answered. */
	val isOutstanding: Boolean
		get() = recordedAtMillis != null

	/**
	 * Remembers a tap. A second tap while one is outstanding keeps the first stamp, so the age
	 * [answer] reports is how long the user has been waiting, not how long since they last tapped.
	 */
	fun record() {
		if (recordedAtMillis == null) recordedAtMillis = nowMillis()
	}

	/** Forgets the tap without answering it: a stop, a park, a failed build, a torn-down session. */
	fun withdraw() {
		recordedAtMillis = null
	}

	/**
	 * Settles the tap because the proxy app is being brought forward for it.
	 *
	 * @return how long the tap waited, in milliseconds, or null when nothing was outstanding.
	 */
	fun answer(): Long? {
		val recordedAt = recordedAtMillis ?: return null
		recordedAtMillis = null
		return nowMillis() - recordedAt
	}
}
