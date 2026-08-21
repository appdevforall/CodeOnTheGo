package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Robolectric for the real `org.json` (see [BenchEventsFileTest]). */
@RunWith(RobolectricTestRunner::class)
class BenchQuickBuildMetricsSinkTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	private lateinit var file: File
	private lateinit var sink: BenchQuickBuildMetricsSink

	@Before
	fun setup() {
		file = File(tempDir.root, "bench-events.jsonl")
		sink = BenchQuickBuildMetricsSink(BenchEventsFile(file) { 42L })
	}

	private fun last(): JSONObject = JSONObject(file.readLines().last())

	@Test
	fun `session_started carries only the envelope`() {
		sink.onSessionStarted()

		val o = last()
		assertThat(o.getString("event")).isEqualTo("session_started")
		assertThat(o.getInt("v")).isEqualTo(1)
		assertThat(o.getLong("wallMs")).isEqualTo(42)
	}

	@Test
	fun `build_started carries buildId and the pinned route wire name`() {
		sink.onBuildStarted(7, BuildRoute.CodeAndResources, ChangedFiles.Known(emptySet()))

		val o = last()
		assertThat(o.getString("event")).isEqualTo("build_started")
		assertThat(o.getLong("buildId")).isEqualTo(7)
		assertThat(o.getString("route")).isEqualTo("CodeAndResources")
	}

	@Test
	fun `build_finished carries buildId and the pinned outcome wire name`() {
		sink.onBuildFinished(7, BuildOutcome.Success(generation = 3, durationMillis = 100))

		val o = last()
		assertThat(o.getString("event")).isEqualTo("build_finished")
		assertThat(o.getLong("buildId")).isEqualTo(7)
		assertThat(o.getString("outcome")).isEqualTo("Success")
	}

	// The three pin tests below are the frozen bench wire contract: the harness
	// (run_e2e_bench.py) string-compares these values and historical .events.jsonl
	// files carry them. A rename of any route/outcome/reason identifier must keep
	// these tables green by mapping the new identifier to the OLD string in
	// BenchQuickBuildMetricsSink.wireName().

	@Test
	fun `build_started pins the wire string of every route`() {
		val pinned: List<Pair<BuildRoute, String>> =
			listOf(
				BuildRoute.FullGradleBuild(InvalidationReason.MANIFEST_CHANGED) to "FullGradleBuild",
				BuildRoute.ResourcesOnly to "ResourcesOnly",
				BuildRoute.AssetsOnly to "AssetsOnly",
				BuildRoute.CodeOnly to "CodeOnly",
				BuildRoute.CodeAndResources to "CodeAndResources",
				BuildRoute.NoOp to "NoOp",
				BuildRoute.WarmCompile to "Seed",
			)
		// The table must cover every route class, or a new route would ship unpinned.
		assertThat(pinned.map { it.first::class })
			.containsExactlyElementsIn(BuildRoute::class.sealedSubclasses)

		pinned.forEach { (route, wire) ->
			sink.onBuildStarted(1, route, ChangedFiles.Known(emptySet()))
			assertThat(last().getString("route")).isEqualTo(wire)
		}
	}

	@Test
	fun `build_finished pins the wire string of every outcome`() {
		val pinned: List<Pair<BuildOutcome, String>> =
			listOf(
				BuildOutcome.Success(generation = 1, durationMillis = 10) to "Success",
				BuildOutcome.RequiresProxyAppRebuild(InvalidationReason.MANIFEST_CHANGED, detail = "d") to "RequiresRebaseline",
				BuildOutcome.CompileError(emptyList()) to "CompileError",
				BuildOutcome.DeployFailure("deploy failed") to "DeployFailure",
				BuildOutcome.InfrastructureFailure("io error") to "InfrastructureFailure",
			)
		// The table must cover every outcome class, or a new outcome would ship unpinned.
		assertThat(pinned.map { it.first::class })
			.containsExactlyElementsIn(BuildOutcome::class.sealedSubclasses)

		pinned.forEach { (outcome, wire) ->
			sink.onBuildFinished(1, outcome)
			assertThat(last().getString("outcome")).isEqualTo(wire)
		}
	}

	@Test
	fun `invalidation pins the wire string of every reason`() {
		val pinned: Map<InvalidationReason, String> =
			mapOf(
				InvalidationReason.MANIFEST_CHANGED to "MANIFEST_CHANGED",
				InvalidationReason.GRADLE_CONFIG_CHANGED to "GRADLE_CONFIG_CHANGED",
				InvalidationReason.UNSUPPORTED_FILE_CHANGED to "UNSUPPORTED_FILE_CHANGED",
				InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED to "NON_APP_MODULE_SOURCE_CHANGED",
				InvalidationReason.EXTERNAL_FULL_BUILD to "EXTERNAL_FULL_BUILD",
				InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED to "ANNOTATION_PROCESSOR_INPUT_CHANGED",
				InvalidationReason.OUTDATED_BASELINE to "OUTDATED_BASELINE",
				InvalidationReason.RELOAD_PIPELINE_FAILED to "RELOAD_PIPELINE_FAILED",
				InvalidationReason.INSTALL_NOT_CONFIRMED to "INSTALL_NOT_CONFIRMED",
			)
		// The table must cover every reason, or a new reason would ship unpinned.
		assertThat(pinned.keys).containsExactlyElementsIn(InvalidationReason.entries)

		pinned.forEach { (reason, wire) ->
			sink.onInvalidation(reason)
			assertThat(last().getString("reason")).isEqualTo(wire)
		}
	}

	@Test
	fun `build_finished carries the compile counts of a FAILING build`() {
		// The point of the whole change: a failing build is where kotlinDeclaredChanged
		// decides the fix. 0 means the edited .kt never entered the dirty set we handed the
		// engine (fix upstream, in changed-set assembly); >= 1 means it did and the staleness
		// is downstream. Without this the two are indistinguishable from a run.
		//
		// NOT emitted as a reload_timeline, deliberately: run_e2e_bench.py:1990 sets
		// status = MEASURED from the mere PRESENCE of a timeline and reads
		// timeline["generation"] at :1981, so a timeline on a failing build would either
		// manufacture a measurement out of a failure or crash the harness.
		sink.onBuildFinished(
			11,
			BuildOutcome.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "cannot be applied to given types")),
				kotlinDeclaredChanged = 0,
				allSources = 5,
				javaSources = 2,
			),
		)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("build_finished")
		assertThat(o.getString("outcome")).isEqualTo("CompileError")
		assertThat(o.getInt("nKotlinCompiled")).isEqualTo(0)
		// A count without its denominator cannot be read: 0 of how many Kotlin sources?
		assertThat(o.getInt("nAllSources")).isEqualTo(5)
		assertThat(o.getInt("nJavaSources")).isEqualTo(2)
		// The detail must survive alongside the counts, not be traded for them.
		assertThat(o.getString("detail")).contains("cannot be applied")
	}

	@Test
	fun `build_finished omits the compile counts when the daemon did not report them`() {
		// Absent, not zero. A CompileError raised before the daemon answered has no counts,
		// and emitting 0 would be a measured zero - the exact ambiguity this exists to remove.
		sink.onBuildFinished(12, BuildOutcome.CompileError(emptyList()))

		val o = last()
		assertThat(o.has("nKotlinCompiled")).isFalse()
		assertThat(o.has("nAllSources")).isFalse()
		assertThat(o.has("nJavaSources")).isFalse()
	}

	@Test
	fun `build_finished quotes the first error of a compile failure, past its warnings`() {
		sink.onBuildFinished(
			9,
			BuildOutcome.CompileError(
				listOf(
					BuildDiagnostic(BuildDiagnostic.Severity.WARNING, "variable never used"),
					BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "unresolved reference: foo"),
					BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "unresolved reference: bar"),
				),
			),
		)

		val o = last()
		assertThat(o.getString("outcome")).isEqualTo("CompileError")
		// The first ERROR, not the first diagnostic: a warning is not why the build failed,
		// and the outcome name alone cannot tell two compile failures apart.
		assertThat(o.getString("detail")).isEqualTo("unresolved reference: foo")
	}

	@Test
	fun `build_finished omits the detail when a compile failure carries no error`() {
		sink.onBuildFinished(
			9,
			BuildOutcome.CompileError(
				listOf(BuildDiagnostic(BuildDiagnostic.Severity.WARNING, "variable never used")),
			),
		)

		val o = last()
		assertThat(o.getString("outcome")).isEqualTo("CompileError")
		// Additive field: a warnings-only list says nothing about the cause, so no key at
		// all rather than a warning the harness would read as the reason.
		assertThat(o.has("detail")).isFalse()
	}

	@Test
	fun `reload_timeline carries every timeline field plus derived totalMs`() {
		sink.onReloadTimeline(
			E2eTimeline(generation = 42, trigger = 1_000, compileDone = 1_600, deploySent = 1_650, reloadLive = 1_720),
		)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("reload_timeline")
		assertThat(o.getLong("generation")).isEqualTo(42)
		assertThat(o.getLong("trigger")).isEqualTo(1_000)
		assertThat(o.getLong("compileDone")).isEqualTo(1_600)
		assertThat(o.getLong("deploySent")).isEqualTo(1_650)
		assertThat(o.getLong("reloadLive")).isEqualTo(1_720)
		assertThat(o.getLong("totalMs")).isEqualTo(720)
		// No steps reported: none of the sub-step fields appear.
		assertThat(o.has("kotlinMs")).isFalse()
		assertThat(o.has("d8Ms")).isFalse()
	}

	@Test
	fun `reload_timeline carries reported sub-step timings and omits unreported ones`() {
		sink.onReloadTimeline(
			E2eTimeline(
				generation = 43,
				trigger = 1_000,
				compileDone = 1_600,
				deploySent = 1_650,
				reloadLive = 1_720,
				steps =
					E2eTimeline.StepTimings(
						kotlinMillis = 400,
						javaMillis = null,
						stripMillis = 20,
						d8Millis = 150,
						aapt2CompileMillis = null,
						aapt2LinkMillis = null,
					),
			),
		)

		val o = last()
		assertThat(o.getLong("kotlinMs")).isEqualTo(400)
		assertThat(o.getLong("stripMs")).isEqualTo(20)
		assertThat(o.getLong("d8Ms")).isEqualTo(150)
		assertThat(o.has("javacMs")).isFalse()
		assertThat(o.has("aapt2CompileMs")).isFalse()
		assertThat(o.has("aapt2LinkMs")).isFalse()
	}

	@Test
	fun `reload_timeline carries the host spans, the residual and the daemon counts`() {
		sink.onReloadTimeline(
			E2eTimeline(
				generation = 44,
				trigger = 0,
				compileDone = 14_700,
				deploySent = 14_700,
				reloadLive = 14_720,
				steps =
					E2eTimeline.StepTimings(
						preSnapMillis = 120,
						postSnapMillis = 130,
						javaAbiSnapMillis = 621,
					),
				spans =
					E2eTimeline.HostSpans(
						scanMillis = 240,
						compileRpcMillis = 4_900,
						policyMillis = 610,
						dexRpcMillis = 8_800,
						relinkRpcMillis = 150,
					),
				counts =
					E2eTimeline.BuildCounts(
						allSources = 292,
						kotlinDeclaredChanged = 0,
						javaSources = 218,
						changedClasses = 323,
						classFiles = 464,
						classBytes = 1_530_112,
						compileOrdinal = 2,
					),
				scratchFsType = "fuse",
			),
		)

		val o = last()
		assertThat(o.getLong("scanMs")).isEqualTo(240)
		assertThat(o.getLong("compileRpcMs")).isEqualTo(4_900)
		assertThat(o.getLong("policyMs")).isEqualTo(610)
		assertThat(o.getLong("dexRpcMs")).isEqualTo(8_800)
		assertThat(o.getLong("relinkRpcMs")).isEqualTo(150)
		// The spans plus the reload tail cover the whole loop: nothing is hiding.
		assertThat(o.getLong("accountedMs")).isEqualTo(14_720)
		assertThat(o.getLong("unaccountedMs")).isEqualTo(0)
		// The bench event keeps the two walks separate; only the Firebase event sums them.
		assertThat(o.getLong("preSnapMs")).isEqualTo(120)
		assertThat(o.getLong("postSnapMs")).isEqualTo(130)
		assertThat(o.getLong("javaAbiSnapMs")).isEqualTo(621)
		assertThat(o.getLong("nAllSources")).isEqualTo(292)
		assertThat(o.getLong("nKotlinDeclaredChanged")).isEqualTo(0)
		assertThat(o.getLong("nJavaSources")).isEqualTo(218)
		assertThat(o.getLong("nChangedClasses")).isEqualTo(323)
		assertThat(o.getLong("nClassFiles")).isEqualTo(464)
		assertThat(o.getLong("classBytes")).isEqualTo(1_530_112)
		assertThat(o.getLong("compileOrdinal")).isEqualTo(2)
		assertThat(o.getString("scratchFs")).isEqualTo("fuse")
	}

	@Test
	fun `reload_timeline omits the residual entirely when no span was measured`() {
		// A pre-instrumentation daemon: reporting unaccountedMs here would read as "the
		// whole build is unexplained" rather than "nothing was measured".
		sink.onReloadTimeline(
			E2eTimeline(generation = 45, trigger = 0, compileDone = 100, deploySent = 110, reloadLive = 120),
		)

		val o = last()
		assertThat(o.has("unaccountedMs")).isFalse()
		assertThat(o.has("accountedMs")).isFalse()
		assertThat(o.has("scanMs")).isFalse()
		assertThat(o.has("scratchFs")).isFalse()
	}

	// Every optional metric field below is additive: absent when the step, span or counter
	// did not report. A field only ever exercised in one of those two states is one the
	// harness could read wrongly - either a missing key it treats as zero, or a key it never
	// learns to expect. The two tests below drive both states over the whole field set.

	/** JSON key -> the value [allReported] puts on it. Distinct values, so a mis-keyed put fails. */
	private val optionalNumbers: Map<String, Long> =
		mapOf(
			"kotlinMs" to 401L,
			"javacMs" to 402L,
			"stripMs" to 403L,
			"d8Ms" to 404L,
			"aapt2CompileMs" to 405L,
			"aapt2LinkMs" to 406L,
			"preSnapMs" to 407L,
			"postSnapMs" to 408L,
			"javaAbiSnapMs" to 409L,
			"scanMs" to 411L,
			"compileRpcMs" to 412L,
			"policyMs" to 413L,
			"dexRpcMs" to 414L,
			"relinkRpcMs" to 415L,
			"nAllSources" to 421L,
			"nKotlinDeclaredChanged" to 422L,
			"nJavaSources" to 423L,
			"nChangedClasses" to 424L,
			"nClassFiles" to 425L,
			"classBytes" to 426L,
			"compileOrdinal" to 427L,
		)

	/** Keys a `reload_timeline` always carries, so [optionalNumbers] accounts for the rest. */
	private val alwaysPresent =
		setOf(
			"v",
			"wallMs",
			"event",
			"generation",
			"trigger",
			"compileDone",
			"deploySent",
			"reloadLive",
			"totalMs",
			"accountedMs",
			"unaccountedMs",
			"scratchFs",
		)

	private fun allReported() =
		E2eTimeline(
			generation = 50,
			trigger = 0,
			compileDone = 900,
			deploySent = 950,
			reloadLive = 1_000,
			steps =
				E2eTimeline.StepTimings(
					kotlinMillis = 401,
					javaMillis = 402,
					stripMillis = 403,
					d8Millis = 404,
					aapt2CompileMillis = 405,
					aapt2LinkMillis = 406,
					preSnapMillis = 407,
					postSnapMillis = 408,
					javaAbiSnapMillis = 409,
				),
			spans =
				E2eTimeline.HostSpans(
					scanMillis = 411,
					compileRpcMillis = 412,
					policyMillis = 413,
					dexRpcMillis = 414,
					relinkRpcMillis = 415,
				),
			counts =
				E2eTimeline.BuildCounts(
					allSources = 421,
					kotlinDeclaredChanged = 422,
					javaSources = 423,
					changedClasses = 424,
					classFiles = 425,
					classBytes = 426,
					compileOrdinal = 427,
				),
			scratchFsType = "ext4",
		)

	@Test
	fun `reload_timeline carries every optional field a fully reported build has`() {
		sink.onReloadTimeline(allReported())

		val o = last()
		optionalNumbers.forEach { (key, value) ->
			assertThat(o.has(key)).isTrue()
			assertThat(o.getLong(key)).isEqualTo(value)
		}
		assertThat(o.getString("scratchFs")).isEqualTo("ext4")
		// The table must account for every optional key, or a newly added metric would ship
		// with only one of its two states ever exercised.
		assertThat(o.keys().asSequence().toSet() - alwaysPresent)
			.containsExactlyElementsIn(optionalNumbers.keys)
	}

	@Test
	fun `reload_timeline omits every optional field a build reported nothing for`() {
		// The containers are present but empty, which is a route that ran a step without
		// timing it - distinct from the null containers the tests above cover.
		sink.onReloadTimeline(
			allReported().copy(
				steps = E2eTimeline.StepTimings(),
				spans = E2eTimeline.HostSpans(),
				counts = E2eTimeline.BuildCounts(),
				scratchFsType = null,
			),
		)

		val o = last()
		optionalNumbers.keys.forEach { key ->
			assertThat(o.has(key)).isFalse()
		}
		assertThat(o.has("scratchFs")).isFalse()
		// A present-but-empty spans object still reports the residual, unlike a null one:
		// no span measured anything, so the whole loop minus the reload reads as unaccounted.
		assertThat(o.getLong("accountedMs")).isEqualTo(50)
		assertThat(o.getLong("unaccountedMs")).isEqualTo(950)
	}

	@Test
	fun `rebaseline event carries ok, duration, and the relaunch fields`() {
		sink.onProxyAppRebuild(isSuccess = true, durationMillis = 7_500, relaunchOk = true, toRunningMillis = 9_200)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("rebaseline")
		assertThat(o.getBoolean("ok")).isTrue()
		assertThat(o.getLong("durationMillis")).isEqualTo(7_500)
		assertThat(o.getBoolean("relaunchOk")).isTrue()
		assertThat(o.getLong("toRunningMillis")).isEqualTo(9_200)
	}

	@Test
	fun `a failed relaunch books relaunchOk false and omits toRunningMillis entirely`() {
		sink.onProxyAppRebuild(isSuccess = true, durationMillis = 7_500, relaunchOk = false, toRunningMillis = null)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("rebaseline")
		assertThat(o.getBoolean("ok")).isTrue()
		assertThat(o.getBoolean("relaunchOk")).isFalse()
		assertThat(o.has("toRunningMillis")).isFalse()
	}

	@Test
	fun `invalidation carries the reason name`() {
		sink.onInvalidation(InvalidationReason.MANIFEST_CHANGED)

		val o = last()
		assertThat(o.getString("event")).isEqualTo("invalidation")
		assertThat(o.getString("reason")).isEqualTo("MANIFEST_CHANGED")
	}
}
