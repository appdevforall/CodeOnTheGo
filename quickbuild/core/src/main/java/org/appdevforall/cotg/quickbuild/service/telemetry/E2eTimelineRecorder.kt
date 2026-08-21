package org.appdevforall.cotg.quickbuild.service.telemetry

import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.appdevforall.cotg.quickbuild.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.protocol.DexStats

/**
 * Collects one build's timings as it moves through the pipeline, then mints an [E2eTimeline].
 *
 * Not thread-safe, and does not need to be: the executor contract allows at most one build in
 * flight. A route that never compiles skips [markCompileDone], so `compileDone` falls back to
 * `deploySent` and compileMillis then measures relink plus package (see [E2eTimeline]).
 *
 * @param trigger the request's t0, in the same clock as the later marks
 * @param scratchFsType read once at [completed] rather than at construction, so it reports
 *   the filesystem the daemon actually landed its output tree on
 */
internal class E2eTimelineRecorder(
	private val trigger: Long,
	private val scratchFsType: () -> String?,
) {
	private var compileDone: Long? = null
	private var deploySent: Long = trigger
	private var steps = E2eTimeline.StepTimings()
	private var spans = E2eTimeline.HostSpans()
	private var counts = E2eTimeline.BuildCounts()

	/**
	 * Stamps t1, the moment a deployable dex exists.
	 *
	 * @param now the mark, in the same clock as `trigger`; a route that never compiles
	 *   leaves this unset and `compileDone` then falls back to `deploySent`
	 */
	fun markCompileDone(now: Long) {
		compileDone = now
	}

	/**
	 * Stamps t2, immediately before the payload goes over the deploy channel.
	 *
	 * @param now the mark, in the same clock as `trigger`
	 */
	fun markDeploySent(now: Long) {
		deploySent = now
	}

	/**
	 * Records the wait from t0 until the build started - queueing, not work.
	 *
	 * @param millis host-observed span; the caller skips this when the request carries no
	 *   trigger stamp, since there is then no t0 to measure from
	 */
	fun recordQueue(millis: Long) {
		spans = spans.copy(queueMillis = millis)
	}

	/**
	 * Records the source-tree walk that precedes the compile.
	 *
	 * @param millis host-observed span, not a daemon-reported one
	 */
	fun recordScan(millis: Long) {
		spans = spans.copy(scanMillis = millis)
	}

	/**
	 * Records the whole compile round trip to the daemon.
	 *
	 * @param millis host-observed span; the daemon's own kotlin/java steps nest inside it
	 */
	fun recordCompileRpc(millis: Long) {
		spans = spans.copy(compileRpcMillis = millis)
	}

	/**
	 * Records the hot-swap-versus-restart decision, including the class-header parses it
	 * needs.
	 *
	 * @param millis host-observed span
	 */
	fun recordPolicy(millis: Long) {
		spans = spans.copy(policyMillis = millis)
	}

	/**
	 * Records the whole dex round trip to the daemon.
	 *
	 * @param millis host-observed span; the daemon's strip and d8 steps nest inside it
	 */
	fun recordDexRpc(millis: Long) {
		spans = spans.copy(dexRpcMillis = millis)
	}

	/**
	 * Records the whole relink round trip to the daemon.
	 *
	 * @param millis host-observed span; the aapt2 compile and link steps nest inside it
	 */
	fun recordRelinkRpc(millis: Long) {
		spans = spans.copy(relinkRpcMillis = millis)
	}

	/**
	 * Records the daemon's own breakdown of one compile, and the source counts that go
	 * with it.
	 *
	 * @param kotlinMillis kotlinc's span, or null when no Kotlin source was compiled
	 * @param javaMillis javac's span, or null when no Java source was compiled
	 * @param stats the daemon's snapshot spans and counts; null leaves every derived field
	 *   unset rather than zero, so a missing measurement never reads as a fast one
	 */
	fun recordCompileSteps(
		kotlinMillis: Long?,
		javaMillis: Long?,
		stats: CompileStats?,
	) {
		steps =
			steps.copy(
				kotlinMillis = kotlinMillis,
				javaMillis = javaMillis,
				preSnapMillis = stats?.preSnapMillis,
				postSnapMillis = stats?.postSnapMillis,
				javaAbiSnapMillis = stats?.javaAbiSnapMillis,
			)
		counts =
			counts.copy(
				allSources = stats?.allSources,
				kotlinDeclaredChanged = stats?.kotlinToCompile,
				javaSources = stats?.javaSources,
				changedClasses = stats?.changedClasses,
				compileOrdinal = stats?.compileOrdinal,
			)
	}

	/**
	 * Records the daemon's own breakdown of one dex step, and the class counts that go
	 * with it.
	 *
	 * @param stripMillis span of the class-stripping pass, or null when unreported
	 * @param d8Millis d8's span, or null when unreported
	 * @param stats the daemon's class-file and byte counts; null leaves both unset
	 */
	fun recordDexSteps(
		stripMillis: Long?,
		d8Millis: Long?,
		stats: DexStats?,
	) {
		steps = steps.copy(stripMillis = stripMillis, d8Millis = d8Millis)
		counts = counts.copy(classFiles = stats?.classFiles, classBytes = stats?.classBytes)
	}

	/**
	 * Records the daemon's own breakdown of one relink.
	 *
	 * @param aapt2CompileMillis aapt2's resource-compile span, or null when unreported
	 * @param aapt2LinkMillis aapt2's link span, or null when unreported
	 */
	fun recordRelinkSteps(
		aapt2CompileMillis: Long?,
		aapt2LinkMillis: Long?,
	) {
		steps = steps.copy(aapt2CompileMillis = aapt2CompileMillis, aapt2LinkMillis = aapt2LinkMillis)
	}

	/**
	 * Builds the finished timeline, stamping [reloadLive] as the last mark.
	 *
	 * @param generation the generation that went live, which keys the emitted line
	 * @param reloadLive t3, in the same clock as `trigger`: the moment the proxy app
	 *   confirmed the new code is running
	 * @return the timeline to emit; empty step, span, and count groups are dropped rather
	 *   than reported as zeros
	 */
	fun completed(
		generation: Long,
		reloadLive: Long,
	): E2eTimeline =
		E2eTimeline(
			generation = generation,
			trigger = trigger,
			compileDone = compileDone ?: deploySent,
			deploySent = deploySent,
			reloadLive = reloadLive,
			steps = steps.takeUnless { it.isEmpty() },
			spans = spans.takeUnless { it.isEmpty() },
			counts = counts.takeUnless { it.isEmpty() },
			scratchFsType = scratchFsType(),
		)
}
