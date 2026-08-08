package org.appdevforall.cotg.quickbuild.domain

/**
 * One generation's end-to-end reload timeline: the four timestamps that bound the live-reload
 * loop, from the file-watch trigger to new code running in the proxy app.
 *
 * All four stamps come off one monotonic device clock (`SystemClock.elapsedRealtime`; an
 * injected fake in tests), so their differences are meaningful with no cross-process clock
 * sync. Absolute values compare only within a single boot - read the deltas, never the stamps.
 *
 * @property generation the deploy generation this loop delivered; strictly increasing per
 *   session, and the key a harness joins a row to its build by.
 * @property trigger t0: the watcher event time of the earliest not-yet-built change this build
 *   coalesced, so the wait behind a slow in-flight build is included.
 * @property compileDone t1: compile and dex finished. Equals [deploySent] on a route that runs
 *   no compile, where [compileMillis] then measures relink and packaging instead.
 * @property deploySent t2: immediately before the payload goes over the binder deploy channel.
 * @property reloadLive t3: the proxy app confirmed the new code is live - a hot-swap
 *   `reportReloaded` from the recreated activity's onResume, or a verified restart reconnect
 *   at the deployed generation.
 */
data class E2eTimeline(
	val generation: Long,
	val trigger: Long,
	val compileDone: Long,
	val deploySent: Long,
	val reloadLive: Long,
	/**
	 * Per-tool step durations as the daemon reported them; null when no step reported one (a
	 * pre-timing daemon, or a route that ran no tools).
	 *
	 * Deliberately not part of [format]/[parse]: the five-stamp log line is a frozen harness
	 * contract, so step timings travel only through the structured metrics sinks.
	 */
	val steps: StepTimings? = null,
	/**
	 * The host-side spans that partition the build half of the loop. Null when unmeasured.
	 * Distinct from [steps], which nest inside them - see [accountedMillis].
	 */
	val spans: HostSpans? = null,
	/** How much work this build did, for reading a slow row. Null when unreported. */
	val counts: BuildCounts? = null,
	/**
	 * Filesystem the daemon's scratch tree lives on (`ext4`, `f2fs`, `fuse`, ...); null when
	 * the daemon did not report one.
	 *
	 * Session-constant, but carried per row because it predicts every duration here: the
	 * daemon's per-file work costs about 52x more on FUSE-backed emulated storage
	 * (measured under ADFA-4128).
	 */
	val scratchFsType: String? = null,
) {
	/**
	 * One build's per-tool durations; a null field means that step did not run or report.
	 *
	 * These nest inside [HostSpans] - kotlin/java/preSnap/postSnap/javaAbiSnap inside
	 * [HostSpans.compileRpcMillis], strip/d8 inside [HostSpans.dexRpcMillis], the aapt2 pair
	 * inside [HostSpans.relinkRpcMillis] - so never add them to an accounting sum. That is
	 * what [accountedMillis] is for.
	 *
	 * @property kotlinMillis the Kotlin incremental compile.
	 * @property javaMillis the Java compile, which recompiles every `.java` source today.
	 * @property stripMillis stripping the class tree down to what d8 is fed.
	 * @property d8Millis dexing that stripped tree.
	 * @property aapt2CompileMillis compiling the changed resources; absent on a code-only route.
	 * @property aapt2LinkMillis relinking the resource table; absent on a code-only route.
	 * @property preSnapMillis output-tree walk before the compile.
	 * @property postSnapMillis output-tree walk after it, which yields the changed-class set.
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

		/**
		 * True when no step reported a duration.
		 *
		 * @return true when every field is null, which a sink reads as "the daemon reported no
		 *   step timings" rather than as a build that took no time.
		 */
		fun isEmpty(): Boolean =
			kotlinMillis == null && javaMillis == null && stripMillis == null &&
				d8Millis == null && aapt2CompileMillis == null && aapt2LinkMillis == null &&
				preSnapMillis == null && postSnapMillis == null && javaAbiSnapMillis == null
	}

	/**
	 * The host-observed spans of one build, measured around each step the executor drives.
	 *
	 * They are mutually exclusive and all sit inside `[trigger, deploySent]`, so with
	 * [reloadMillis] they account for [totalMillis] - which is what makes [unaccountedMillis]
	 * meaningful.
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

		/**
		 * True when no span was measured.
		 *
		 * @return true when every field is null, which is what makes [unaccountedMillis] report
		 *   zero rather than the whole loop.
		 */
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
	 * @property compileOrdinal 1-based compile index within the daemon session. `1` is the
	 *   session's cold build, which seeds the incremental caches, so it must not be read as a
	 *   warm edit.
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
		/**
		 * True when the build reported no counters.
		 *
		 * @return true when every field is null; a build that genuinely compiled nothing still
		 *   reports zeros, so the two cases stay distinguishable.
		 */
		fun isEmpty(): Boolean =
			allSources == null && kotlinCompiled == null && javaSources == null &&
				changedClasses == null && classFiles == null && classBytes == null &&
				compileOrdinal == null
	}

	/** Trigger -> compiled+dexed (or relinked, for a no-compile route). */
	val compileMillis: Long get() = compileDone - trigger

	/** Compiled -> about to deploy: relink + asset packaging on a mixed route, ~0 on code-only. */
	val stageMillis: Long get() = deploySent - compileDone

	/** Deploy handed off -> confirmed live: binder round-trip + the proxy app's reload. */
	val reloadMillis: Long get() = reloadLive - deploySent

	/** The whole loop the user feels: file change -> new code on screen. */
	val totalMillis: Long get() = reloadLive - trigger

	/**
	 * How much of [totalMillis] a named span actually measured: the host spans, which
	 * partition `[trigger, deploySent]`, plus [reloadMillis] for the rest.
	 *
	 * [steps] are excluded on purpose - they nest inside the host spans, so counting them
	 * would double-count.
	 */
	val accountedMillis: Long get() = (spans?.totalMillis ?: 0) + reloadMillis

	/**
	 * The part of the loop no span measured - the field this event exists for.
	 *
	 * Reporting it keeps unmeasured work visible: the per-tool timings alone cover only about
	 * half a warm edit `[measured on a56]`, with source scan, ABI snapshot, output-tree walks
	 * and the deploy-policy class-header pass having no span of their own. A near-zero
	 * residual is the healthy state; one that grows means a step is running that nothing
	 * times. Zero when [spans] is null, since nothing was measured.
	 */
	val unaccountedMillis: Long get() = if (spans == null) 0 else totalMillis - accountedMillis

	/**
	 * The single structured line CoGo logs per generation. Grep-stable: the harness keys
	 * on the literal `[LOG_TAG]` prefix, and every field is `name=<long>` so a regex parse
	 * is unambiguous.
	 *
	 * @return the log line, [LOG_TAG] first and the five stamps after it. Carries no step
	 *   timings, spans or counts - widening it would break the harness's parser.
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
		 * (timestamp/pid/tag) around it.
		 *
		 * @param line one raw logcat line; anything outside the five-field shape is ignored.
		 * @return the timeline, or null when the line lacks [LOG_TAG] or the full five-field
		 *   shape - a partial or unrelated line is not a timeline. [steps], [spans] and [counts]
		 *   are always null, since the line does not carry them.
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
