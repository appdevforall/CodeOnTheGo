package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline

/**
 * Collects one build's timings as it moves through the pipeline, then mints an
 * [E2eTimeline].
 *
 * Not thread-safe, and does not need to be: the
 * [org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor] contract allows at most one
 * build in flight. A route that never compiles skips [markCompileDone], so `compileDone`
 * falls back to `deploySent` and compileMillis then measures relink plus package (see
 * [E2eTimeline]).
 *
 * @param trigger the request's t0, in the same clock as the later marks
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

	fun markCompileDone(now: Long) {
		compileDone = now
	}

	fun markDeploySent(now: Long) {
		deploySent = now
	}

	fun recordScan(millis: Long) {
		spans = spans.copy(scanMillis = millis)
	}

	fun recordCompileRpc(millis: Long) {
		spans = spans.copy(compileRpcMillis = millis)
	}

	fun recordPolicy(millis: Long) {
		spans = spans.copy(policyMillis = millis)
	}

	fun recordDexRpc(millis: Long) {
		spans = spans.copy(dexRpcMillis = millis)
	}

	fun recordRelinkRpc(millis: Long) {
		spans = spans.copy(relinkRpcMillis = millis)
	}

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
				kotlinCompiled = stats?.kotlinToCompile,
				javaSources = stats?.javaSources,
				changedClasses = stats?.changedClasses,
				compileOrdinal = stats?.compileOrdinal,
			)
	}

	fun recordDexSteps(
		stripMillis: Long?,
		d8Millis: Long?,
		stats: DexStats?,
	) {
		steps = steps.copy(stripMillis = stripMillis, d8Millis = d8Millis)
		counts = counts.copy(classFiles = stats?.classFiles, classBytes = stats?.classBytes)
	}

	fun recordRelinkSteps(
		aapt2CompileMillis: Long?,
		aapt2LinkMillis: Long?,
	) {
		steps = steps.copy(aapt2CompileMillis = aapt2CompileMillis, aapt2LinkMillis = aapt2LinkMillis)
	}

	/** Builds the finished timeline, stamping [reloadLive] as the last mark. */
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
