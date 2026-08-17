package com.itsaky.androidide.quickbuild

import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.appdevforall.cotg.quickbuild.domain.telemetry.E2eTimeline
import java.util.Locale

/** Every line this file writes starts with it, so a reader can tell them from Gradle's output. */
private const val PREFIX = "Quick Build: "

/**
 * Narrates a Quick Build session into the Build Output pane - it otherwise leaves no trail, its
 * failures flashing once and its progress living only in a toolbar icon.
 *
 * Keyed on status *transitions*, not states: [QuickBuildStatus] is derived, so the same status
 * arrives repeatedly and only the change is news. The copy is untranslated English because it sits
 * among Gradle's own output, where a single translated line reads worse than a consistent one.
 *
 * @param previous the status before this change; null on the first emission, which is not news.
 * @param current the status now.
 * @return the lines to append, each already newline-terminated; empty when nothing is worth saying.
 */
fun quickBuildOutputLines(
	previous: QuickBuildStatus?,
	current: QuickBuildStatus,
): List<String> {
	if (previous == null) {
		return emptyList()
	}
	val body =
		when (val transition = quickBuildTransition(previous, current)) {
			QuickBuildTransition.None -> {
				emptyList()
			}

			// Only from a live session or a start: a failed-start tone clearing on a save is
			// also a Hidden -> Hidden hop, and narrating that as "session stopped." would
			// invent a session that never existed.
			QuickBuildTransition.SessionStopped -> {
				if (previous is QuickBuildStatus.Hidden) emptyList() else listOf("session stopped.")
			}

			// The Gradle cause was already quoted above by the proxy-app failure narration;
			// this adds the gesture that retries, since the flash naming it is transient.
			QuickBuildTransition.StartFailed -> {
				listOf("could not start - tap Quick Build to retry.")
			}

			is QuickBuildTransition.ProvisioningStarted -> {
				when (val kind = transition.kind) {
					is ProvisioningKind.Rebaseline -> {
						listOf("rebuilding your app with a full Gradle build - ${describe(kind.reason)}.")
					}

					ProvisioningKind.Restart -> {
						listOf("session restarted - running a full build, then an install.")
					}

					ProvisioningKind.Initial -> {
						listOf("running the initial full build, then an install.")
					}
				}
			}

			is QuickBuildTransition.Compiling -> {
				listOf("compiling your save; the app is running generation ${transition.runningGeneration}.")
			}

			is QuickBuildTransition.Settled -> {
				upToDateLines(previous, transition.status)
			}

			is QuickBuildTransition.FailureReported -> {
				if (transition.isRepeat) emptyList() else failureLines(transition.failure)
			}

			is QuickBuildTransition.FullBuildNeeded -> {
				if (transition.awaitingRetry) {
					// The rebuild already ran and failed - its Gradle output is quoted just
					// above. A save with a fix retries by itself, so name that gesture instead
					// of narrating upcoming work.
					listOf("the rebuild failed - save a fix to retry.")
				} else {
					listOf(
						"a full build is needed - ${describe(transition.reason)}. " +
							"Tap Quick Build to rebuild.",
					)
				}
			}

			is QuickBuildTransition.DaemonStopped -> {
				if (transition.restartFailed) {
					listOf(
						"the compile daemon stopped and could not be restarted. Your app keeps " +
							"running; tap Quick Build to try again.",
					)
				} else {
					listOf("the compile daemon stopped; restarting it. Your app keeps running.")
				}
			}
		}
	return body.map { PREFIX + it + "\n" }
}

/**
 * Narrates where a landed save-to-live loop spent its time, as one line under the build that
 * reported it.
 *
 * The status stream carries only the loop's total, so a slow save reads as a number with no
 * explanation; the phases split it into what the user can act on - their code, their resources, or
 * a save that waited behind another one. Every measured phase is listed in the order it ran and the
 * unmeasured rest is named as a remainder: naming only the three daemon round trips left about half
 * of a warm save unexplained, inviting the reader to hunt for the missing seconds.
 *
 * @param timeline the finished save-to-live loop.
 * @return the line, already prefixed and newline-terminated, naming only the phases worth
 *   reporting; null when the loop measured no phase at all.
 */
fun quickBuildTimingLine(timeline: E2eTimeline): String? {
	val spans = timeline.spans ?: return null
	// In loop order, so the line reads as the sequence the save went through. The three daemon
	// round trips report whenever they ran, even at 0.0s - their presence is what says which
	// route this was; the rest report only when they are worth a reader's attention.
	val spanPhases =
		listOfNotNull(
			spans.queueMillis?.takeIf(::worthReporting)?.let { "queued for ${seconds(it)}" to it },
			spans.scanMillis?.takeIf(::worthReporting)?.let { "scanned in ${seconds(it)}" to it },
			spans.compileRpcMillis?.let { "compiled in ${seconds(it)}" to it },
			spans.policyMillis?.takeIf(::worthReporting)?.let { "checked classes in ${seconds(it)}" to it },
			spans.dexRpcMillis?.let { "dexed in ${seconds(it)}" to it },
			spans.relinkRpcMillis?.let { "relinked in ${seconds(it)}" to it },
		)
	if (spanPhases.isEmpty()) {
		// Nothing of the build itself was measured, so a total plus a remainder would only
		// restate the status line's own "reloaded to generation N".
		return null
	}
	val phases =
		spanPhases +
			listOfNotNull(timeline.reloadMillis.takeIf(::worthReporting)?.let { "reloaded in ${seconds(it)}" to it })
	// Against what was PRINTED, not against accountedMillis: a phase folded away for being too
	// small still has to land somewhere, or the printed numbers would not add up to the total.
	val remainder = timeline.totalMillis - phases.sumOf { it.second }
	val named =
		phases.map { it.first } +
			listOfNotNull(remainder.takeIf(::worthReporting)?.let { "other ${seconds(it)}" })
	return PREFIX + "generation ${timeline.generation} - " + named.joinToString(", ") +
		" (${seconds(timeline.totalMillis)} from save to live).\n"
}

/**
 * Whether a phase is big enough to name, rather than fold into the line's remainder.
 *
 * @param millis the phase's duration.
 * @return true when it renders as at least 0.1s; anything smaller would print as `0.0s`, which
 *   is noise in a line the reader scans for the phase that cost them time.
 */
private fun worthReporting(millis: Long): Boolean = millis >= MIN_REPORTED_MILLIS

/** Below this a duration renders as `0.0s`; see [worthReporting]. */
private const val MIN_REPORTED_MILLIS = 50L

/**
 * Narrates why the full Gradle build behind a provision or a rebaseline failed, quoting Gradle.
 *
 * This is the only route that reason has to the user: the proxy app build runs as an INTERNAL
 * build, which suppresses the editor's build listener, so Gradle's output never reaches the pane by
 * itself - and the tooling API's own failure is a bare enum
 * ([com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure]) naming the
 * category, never the cause. So the text below is Gradle's captured output or nothing at all.
 *
 * @param output the internal build's captured Gradle output, oldest line first.
 * @return the header line followed by the salient captured lines, already prefixed and
 *   newline-terminated; never empty, since a failure with nothing captured still says so.
 */
fun quickBuildProxyAppFailureLines(output: List<String>): List<String> {
	val salient = salientFailureLines(output)
	val body =
		if (salient.isEmpty()) {
			listOf(
				"the full Gradle build failed, and Gradle reported no output to quote. " +
					"Run a standard build to see the error.",
			)
		} else {
			listOf("the full Gradle build failed. Gradle said:") + salient.map { "  $it" }
		}
	return body.map { PREFIX + it + "\n" }
}

/**
 * Turns one raw line of a proxy app build's Gradle output into a Build Output progress line.
 *
 * The proxy app build is otherwise silent for its whole duration - 80 s on a fresh project, longer
 * on a slow device - because it runs as an internal build with the editor's listener suppressed,
 * which reads as a hang. Silence was never the intent of the suppression; keeping the editor's
 * build UI out of the way was. Only task-execution lines survive, since Gradle's raw output is
 * mostly chatter and the no-work outcomes bury the tasks that are actually running.
 *
 * @param line one raw Gradle output line.
 * @return the line to append, already prefixed and newline-terminated; null to drop it.
 */
fun quickBuildProxyAppProgressLine(line: String): String? {
	val trimmed = line.trim()
	if (!trimmed.startsWith(TASK_MARKER)) {
		return null
	}
	val task = trimmed.removePrefix(TASK_MARKER).trim()
	if (task.isEmpty() || NO_WORK_OUTCOMES.any { task.endsWith(it) }) {
		return null
	}
	return PREFIX + "  " + task + "\n"
}

/** How Gradle announces a task it is about to run. */
private const val TASK_MARKER = "> Task"

/** Task outcomes that mean no work happened, so reporting them only hides the ones that did. */
private val NO_WORK_OUTCOMES = listOf("UP-TO-DATE", "FROM-CACHE", "NO-SOURCE", "SKIPPED")

/**
 * Gradle's own one-line cause, short enough for a flashbar and a status line.
 *
 * The full quote goes to Build Output ([quickBuildProxyAppFailureLines]); this is what the user
 * reads without opening it, so it names the cause rather than the category. Gradle marks the cause
 * with `> ` under its failure banner, which is the line worth lifting.
 *
 * @param output the internal build's captured Gradle output, oldest line first.
 * @return the cause, trimmed of Gradle's marker and capped at [MAX_SUMMARY_CHARS]; null when
 *   nothing quotable was captured, which leaves the caller's generic wording in place.
 */
fun quickBuildProxyAppFailureSummary(output: List<String>): String? {
	// Only within the failure report: Gradle spends `> ` on progress too ("> Task :app:preBuild"),
	// so a capture with no banner holds no line that is reliably the cause, and lifting the last
	// task that ran would name a passing step as the reason the build failed.
	val cause =
		failureReport(output.map { it.trim() }.filter { it.isNotBlank() })
			.firstOrNull { it.startsWith("> ") }
			?.removePrefix("> ")
			?.trim()
			?: return null
	if (cause.isEmpty()) {
		return null
	}
	return if (cause.length <= MAX_SUMMARY_CHARS) {
		cause
	} else {
		cause.take(MAX_SUMMARY_CHARS - 1).trimEnd() + "…"
	}
}

/**
 * How much of Gradle's cause fits in a flashbar before it stops being readable. The full text is
 * always in Build Output, so truncating here loses nothing.
 */
private const val MAX_SUMMARY_CHARS = 160

/**
 * Picks the lines of a Gradle failure worth quoting, since the captured tail is mostly progress.
 *
 * Gradle puts the cause under a `FAILURE:` banner, so everything from the last one is the report
 * for this build. Without a banner (a crash, a truncated capture) compiler `error:` lines are the
 * next best thing, and failing that nothing is quoted rather than a misleading tail.
 *
 * @param output the captured output, oldest line first.
 * @return the lines to quote, in order, capped at [MAX_QUOTED_FAILURE_LINES].
 */
private fun salientFailureLines(output: List<String>): List<String> {
	val trimmed = output.map { it.trimEnd() }.filter { it.isNotBlank() }
	val report = failureReport(trimmed)
	val picked =
		if (report.isNotEmpty()) {
			report
		} else {
			trimmed.filter { it.contains("error:") || it.startsWith("> ") }
		}
	return picked.take(MAX_QUOTED_FAILURE_LINES)
}

/**
 * Gradle's failure report: everything from the last `FAILURE:` banner, since an earlier banner
 * belongs to an earlier build in the same capture buffer.
 *
 * @param lines the captured output, already trimmed and blank-free, oldest line first.
 * @return the report, oldest line first; empty when the capture holds no banner at all.
 */
private fun failureReport(lines: List<String>): List<String> {
	val banner = lines.indexOfLast { it.startsWith("FAILURE:") }
	return if (banner >= 0) lines.subList(banner, lines.size) else emptyList()
}

/**
 * How many lines of Gradle's failure to quote. Enough for the banner, the "What went wrong"
 * heading and the cause with its detail; short of the "Try:" / stacktrace boilerplate, which is
 * long and tells an on-device user nothing they can act on.
 */
private const val MAX_QUOTED_FAILURE_LINES = 12

/**
 * Renders a duration the way a build log does - seconds to one decimal, not raw milliseconds,
 * since these are read side by side rather than compared.
 *
 * Shared with the status bar ([quickBuildStatusBarUpdate]) so one loop never appears as `1948 ms`
 * on one surface and `3.9s` on another.
 *
 * @param millis the duration.
 * @return the duration as `2.8s`, in a fixed locale so a decimal comma never appears mid-line.
 */
internal fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1fs", millis / 1000.0)

/**
 * Lines for reaching [QuickBuildStatus.UpToDate], which is both "a build just landed" and the
 * session's resting state.
 *
 * @param previous the status before this change.
 * @param current the up-to-date status now.
 * @return the lines to write, empty when arriving here is not news.
 */
private fun upToDateLines(
	previous: QuickBuildStatus,
	current: QuickBuildStatus.UpToDate,
): List<String> {
	val landed = current.buildDurationMillis
	return when {
		// Whether provisioned now or adopted from an earlier run, this is the session opening.
		previous is QuickBuildStatus.Provisioning || previous is QuickBuildStatus.Hidden -> {
			listOf("session ready, running generation ${current.generation}.")
		}

		previous is QuickBuildStatus.Reconnecting -> {
			listOf("the compile daemon is back; session ready.")
		}

		// A duration means a build landed. Without one this is the same generation settling,
		// or a warm compile that deploys nothing.
		landed != null -> {
			val how = if (current.restarted) "restarted on" else "reloaded to"
			// Same quantity and same formatting as the timing line's total, deliberately: two
			// differently-scaled numbers for one loop leave the reader asking which is which.
			listOf("$how generation ${current.generation} in ${seconds(landed)}.")
		}

		else -> {
			emptyList()
		}
	}
}

/**
 * Lines for a failed build: what failed, then the compiler's own messages.
 *
 * The diagnostics are the point - they carry file:line, which is how the user finds what broke.
 *
 * @param failure what went wrong.
 * @return the header line followed by one line per diagnostic.
 */
private fun failureLines(failure: SessionFailure): List<String> =
	when (failure) {
		is SessionFailure.CompileError -> {
			listOf("build failed.") + failure.diagnostics.map { "  " + describe(it) }
		}

		is SessionFailure.DeployError -> {
			listOf("the build succeeded but could not be delivered - ${failure.message}")
		}

		is SessionFailure.ProxyAppCrash -> {
			listOf(
				"the new code crashed and was rolled back - ${failure.summary}. " +
					"The app is running the last working version.",
			)
		}
	}

/**
 * Renders one compiler message as `file:line:column: severity: text`, dropping the parts the
 * compiler did not name.
 *
 * @param diagnostic the compiler message.
 * @return one line, never empty.
 */
private fun describe(diagnostic: BuildDiagnostic): String {
	val location =
		buildString {
			diagnostic.file?.let { append(it) }
			diagnostic.line?.let { append(':').append(it) }
			diagnostic.column?.let { append(':').append(it) }
			if (isNotEmpty()) append(": ")
		}
	val severity = if (diagnostic.severity == BuildDiagnostic.Severity.ERROR) "error" else "warning"
	return "$location$severity: ${diagnostic.message}"
}

/**
 * Names why the live reload path gave up, in the user's terms rather than the enum's.
 *
 * @param reason what the reload path could not absorb.
 * @return a clause that completes "a full build is needed - ...".
 */
private fun describe(reason: InvalidationReason): String =
	when (reason) {
		InvalidationReason.MANIFEST_CHANGED -> {
			"the manifest changed"
		}

		InvalidationReason.GRADLE_CONFIG_CHANGED -> {
			"a Gradle build file changed"
		}

		InvalidationReason.UNSUPPORTED_FILE_CHANGED -> {
			"a file Quick Build cannot package changed"
		}

		InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED -> {
			"another module's source changed"
		}

		InvalidationReason.EXTERNAL_FULL_BUILD -> {
			"a full Gradle build moved the baseline"
		}

		InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED -> {
			"an edit may have changed generated code"
		}

		InvalidationReason.OUTDATED_BASELINE -> {
			"the installed app predates this version of CoGo"
		}

		InvalidationReason.RELOAD_PIPELINE_FAILED -> {
			"the reload path kept failing"
		}

		InvalidationReason.INSTALL_NOT_CONFIRMED -> {
			"the last install was not confirmed"
		}
	}
