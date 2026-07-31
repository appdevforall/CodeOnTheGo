package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.domain.E2eTimeline

/**
 * Accumulates one build's e2e stamps as it flows through the pipeline (safe as a plain
 * object: the [org.appdevforall.cotg.quickbuild.domain.LiveReloadExecutor] contract is
 * at-most-one build in flight). [trigger] is t0 from the request; [markCompileDone] and
 * [markDeploySent] stamp t1/t2; [completed] mints the [E2eTimeline] with t3. A route with
 * no compile never calls [markCompileDone], so [E2eTimeline.compileDone] falls back to
 * deploySent - t1==t2, and compileMillis then measures relink+package (documented on
 * [E2eTimeline]).
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
