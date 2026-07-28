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
	/**
	 * Per-tool step durations inside [compileMillis]/[stageMillis], as the daemon reported
	 * them (see the `quickbuild-daemon` tool wrappers). Null when no step reported a
	 * duration (a pre-timing daemon, or a route that ran no tools); each field is null when
	 * that step did not run. Deliberately NOT part of [format]/[parse] - the five-stamp log
	 * line is a frozen harness contract; step timings travel only through the structured
	 * metrics sinks (the bench `reload_timeline` event).
	 */
	val steps: StepTimings? = null,
	/**
	 * The host-side spans that PARTITION the build half of the loop. Null when unmeasured.
	 * Distinct from [steps], which nests inside them - see [accountedMillis].
	 */
	val spans: HostSpans? = null,
	/** How much work this build did, for reading a slow row. Null when unreported. */
	val counts: BuildCounts? = null,
	/**
	 * Filesystem the daemon's scratch tree lives on (`ext4`, `f2fs`, `fuse`, ...). Null
	 * when the daemon did not report one. Session-constant, carried per row because it is
	 * the strongest single predictor of every duration here: the daemon's per-file work
	 * costs ~52x more on FUSE-backed emulated storage (ADFA-4128 deep-dive).
	 */
	val scratchFsType: String? = null,
) {
	/**
	 * One build's per-tool durations; every field nullable = "that step did not run/report".
	 *
	 * These NEST inside [HostSpans] - kotlin/java/preSnap/postSnap/javaAbiSnap inside
	 * [HostSpans.compileRpcMillis], strip/d8 inside [HostSpans.dexRpcMillis], the aapt2
	 * pair inside [HostSpans.relinkRpcMillis]. Never add them to an accounting sum; that
	 * is what [accountedMillis] is for.
	 *
	 * @property preSnapMillis output-tree walk before the compile.
	 * @property postSnapMillis output-tree walk after it (yields the changed-class set).
	 * @property javaAbiSnapMillis re-parse of every `.java` source's declarations.
	 */
	data class StepTimings(
		val kotlinMillis: Long? = null,
		val javaMillis: Long? = null,
		val stripMillis: Long? = null,
		val d8Millis: Long? = null,
		val aapt2CompileMillis: Long? = null,
		val aapt2LinkMillis: Long? = null,
		val preSnapMillis: Long? = null,
		val postSnapMillis: Long? = null,
		val javaAbiSnapMillis: Long? = null,
	) {
		/** The two output-tree walks as one number; null when neither was reported. */
		val walkMillis: Long?
			get() =
				if (preSnapMillis == null && postSnapMillis == null) {
					null
				} else {
					(preSnapMillis ?: 0) + (postSnapMillis ?: 0)
				}

		fun isEmpty(): Boolean =
			kotlinMillis == null && javaMillis == null && stripMillis == null &&
				d8Millis == null && aapt2CompileMillis == null && aapt2LinkMillis == null &&
				preSnapMillis == null && postSnapMillis == null && javaAbiSnapMillis == null
	}

	/**
	 * The host-observed spans of one build, measured around each step the executor drives.
	 * They are mutually exclusive and all live inside `[trigger, deploySent]`, so together
	 * with [reloadMillis] they account for [totalMillis] - which is what makes
	 * [unaccountedMillis] meaningful.
	 *
	 * @property scanMillis enumerating the project's sources.
	 * @property compileRpcMillis the whole `compile` round trip, daemon time included.
	 * @property policyMillis the deploy policy's pass over every changed class header.
	 * @property dexRpcMillis the whole `dex` round trip.
	 * @property relinkRpcMillis the whole `relink` round trip; absent on code-only routes.
	 */
	data class HostSpans(
		val scanMillis: Long? = null,
		val compileRpcMillis: Long? = null,
		val policyMillis: Long? = null,
		val dexRpcMillis: Long? = null,
		val relinkRpcMillis: Long? = null,
	) {
		/** Sum of the measured spans; an unmeasured one contributes nothing. */
		val totalMillis: Long
			get() =
				(scanMillis ?: 0) + (compileRpcMillis ?: 0) + (policyMillis ?: 0) +
					(dexRpcMillis ?: 0) + (relinkRpcMillis ?: 0)

		fun isEmpty(): Boolean =
			scanMillis == null && compileRpcMillis == null && policyMillis == null &&
				dexRpcMillis == null && relinkRpcMillis == null
	}

	/**
	 * How much work the build did. Counters only - no paths, no names, no content.
	 *
	 * @property allSources sources handed to the compiler.
	 * @property kotlinCompiled Kotlin sources actually recompiled.
	 * @property javaSources `.java` sources, all recompiled every build today.
	 * @property changedClasses `.class` files this build emitted or rewrote.
	 * @property classFiles classes the dex step stripped and dexed - the whole tree.
	 * @property classBytes their total size.
	 * @property compileOrdinal 1-based compile index within the daemon session; `1` is the
	 *   session's cold build (it seeds the incremental caches). Reading a cold build as a
	 *   warm edit is what made a 53 s first build look like a per-edit cost.
	 */
	data class BuildCounts(
		val allSources: Int? = null,
		val kotlinCompiled: Int? = null,
		val javaSources: Int? = null,
		val changedClasses: Int? = null,
		val classFiles: Int? = null,
		val classBytes: Long? = null,
		val compileOrdinal: Long? = null,
	) {
		fun isEmpty(): Boolean =
			allSources == null && kotlinCompiled == null && javaSources == null &&
				changedClasses == null && classFiles == null && classBytes == null &&
				compileOrdinal == null
	}

	/** Trigger -> compiled+dexed (or relinked, for a no-compile route). */
	val compileMillis: Long get() = compileDone - trigger

	/** Compiled -> about to deploy: relink + asset packaging on a mixed route, ~0 on code-only. */
	val stageMillis: Long get() = deploySent - compileDone

	/** Deploy handed off -> confirmed live: binder round-trip + the test app's reload. */
	val reloadMillis: Long get() = reloadLive - deploySent

	/** The whole loop the user feels: file change -> new code on screen. */
	val totalMillis: Long get() = reloadLive - trigger

	/**
	 * How much of [totalMillis] a named span actually measured: the host spans (which
	 * partition `[trigger, deploySent]`) plus [reloadMillis] (which covers the rest).
	 *
	 * Only [spans] and [reloadMillis] appear here. [steps] are daemon-internal and nest
	 * inside the host spans, so adding them would double-count.
	 */
	val accountedMillis: Long get() = (spans?.totalMillis ?: 0) + reloadMillis

	/**
	 * The part of the loop no span measured - THE field this event exists for.
	 *
	 * Quick Build's per-build telemetry used to report four tool timings that summed to
	 * roughly half a warm edit. The other half - source scan, ABI snapshot, output-tree
	 * walks, the deploy-policy class-header pass - was not merely unmeasured but
	 * invisible, and a design note read the visible half and concluded javac was "the
	 * bottleneck" when javac is 19-27% of a warm edit (ADFA-4128 deep-dive, section 5).
	 *
	 * A near-zero residual is the healthy state: on the deep-dive's 13 device rows the
	 * measured spans reconciled to the total within 5 ms. A residual that GROWS is the
	 * signal - it means a step is running that nothing times, and the next reader sees the
	 * gap instead of silently misattributing it to whatever is measured next door.
	 *
	 * Known contributors even in the healthy case, all small: changed-asset packaging, and
	 * the payload bookkeeping between the last measured span and the deploy hand-off.
	 *
	 * Zero when [spans] is null (nothing was measured, so nothing is claimed).
	 */
	val unaccountedMillis: Long get() = if (spans == null) 0 else totalMillis - accountedMillis

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
