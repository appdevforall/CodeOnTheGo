package org.appdevforall.cotg.quickbuild.domain

/**
 * One generation's end-to-end reload timeline (ADFA-4128 e2e-timing spec): the four
 * device-local timestamps that bound the live-reload loop, from the file-watch trigger
 * to the new code being live in the running test app.
 *
 * All four stamps originate on the SAME device (the A56) off ONE monotonic clock
 * (`SystemClock.elapsedRealtime` on device; an injected fake in tests), so their
 * differences are meaningful without any cross-process clock sync - CoGo and the test
 * app read the same device-global boot clock. Absolute values are only comparable within
 * a single boot; consumers always read the deltas, never the raw stamps.
 *
 * Stamp definitions (documented here because the harness and the report both cite them):
 * - [trigger] (t0): the watcher event time for the change that started this build's
 *   batch - the earliest not-yet-built change the build coalesced (see
 *   [BuildOrchestrator]). Captures the queue wait a slow in-flight build imposes.
 * - [compileDone] (t1): compile + dex finished (the deployable classes exist). For a
 *   route with no compile (resources/assets only) this equals [deploySent] - there is no
 *   compile phase, so [compileMillis] then measures relink + packaging instead.
 * - [deploySent] (t2): immediately before the payload is handed to the test app over the
 *   binder deploy channel.
 * - [reloadLive] (t3): the test app confirmed the new code is live - a hot-swap
 *   `reportReloaded` (fired from the recreated activity's onResume) or a verified restart
 *   reconnect at the deployed generation.
 */
data class E2eTimeline(
	val generation: Long,
	val trigger: Long,
	val compileDone: Long,
	val deploySent: Long,
	val reloadLive: Long,
) {
	/** Trigger -> compiled+dexed (or relinked, for a no-compile route). */
	val compileMillis: Long get() = compileDone - trigger

	/** Compiled -> about to deploy: relink + asset packaging on a mixed route, ~0 on code-only. */
	val stageMillis: Long get() = deploySent - compileDone

	/** Deploy handed off -> confirmed live: binder round-trip + the test app's reload. */
	val reloadMillis: Long get() = reloadLive - deploySent

	/** The whole loop the user feels: file change -> new code on screen. */
	val totalMillis: Long get() = reloadLive - trigger

	/**
	 * The single structured line CoGo logs per generation. Grep-stable: the harness keys
	 * on the literal `[LOG_TAG]` prefix, and every field is `name=<long>` so a regex parse
	 * is unambiguous.
	 */
	fun format(): String =
		"$LOG_TAG gen=$generation trigger=$trigger compileDone=$compileDone " +
			"deploySent=$deploySent reloadLive=$reloadLive"

	companion object {
		/** The literal prefix of [format]'s line; the harness greps logcat for it. */
		const val LOG_TAG = "quickbuild-e2e:"

		private val LINE =
			Regex(
				"""gen=(-?\d+)\s+trigger=(-?\d+)\s+compileDone=(-?\d+)\s+""" +
					"""deploySent=(-?\d+)\s+reloadLive=(-?\d+)""",
			)

		/**
		 * Parses a [format] line back into a timeline, tolerating a logcat prefix
		 * (timestamp/pid/tag) around it. Returns null for any line that does not carry the
		 * full five-field shape - a partial or unrelated line is not a timeline.
		 */
		fun parse(line: String): E2eTimeline? {
			if (!line.contains(LOG_TAG)) return null
			val m = LINE.find(line) ?: return null
			val (gen, t0, t1, t2, t3) = m.destructured
			return E2eTimeline(
				generation = gen.toLong(),
				trigger = t0.toLong(),
				compileDone = t1.toLong(),
				deploySent = t2.toLong(),
				reloadLive = t3.toLong(),
			)
		}
	}
}
