/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.memprof

/**
 * Formats the machine-readable log lines the memprof harness scripts parse.
 *
 * Kept as pure string formatting, with no Android dependency, so the exact line shapes can be
 * pinned by a JVM unit test instead of only being checked on a device.
 */
object MemprofLines {
	/**
	 * The `PHASE|` line a finished phase emits, parsed by `parse_phases.py`.
	 *
	 * Carries only [details], the values the phase itself put on its span. It never carries the
	 * automatic per-phase duration or allocation: `parse_phases.py` merges every `k=v` pair from
	 * every `PHASE|` line for a run into one dict, so a key repeated across markers would clobber.
	 * The automatic figures go on [phaseEndLine] instead.
	 */
	fun phaseLine(
		marker: String,
		nowMs: Long,
		details: Map<String, Long>,
	): String = "PHASE|$marker|$nowMs" + detailsSuffix(details)

	/** The line an abandoned phase emits instead of [phaseLine], so the harness never mistakes it for a finish. */
	fun phaseAbandonedLine(
		marker: String,
		nowMs: Long,
		details: Map<String, Long>,
	): String = "MEMPROF|phase_abandoned|$marker|$nowMs" + detailsSuffix(details)

	/** The automatic duration and allocation for a phase, kept off [phaseLine] so extras there stay explicit-only. */
	fun phaseEndLine(
		marker: String,
		nowMs: Long,
		durationMs: Long,
		allocBytes: Long,
	): String = "MEMPROF|phase_end|$nowMs|marker=$marker|durationMs=$durationMs|allocBytes=$allocBytes"

	/**
	 * The line one finished section instance emits, read by `alloc_budget.py`'s per-file report.
	 *
	 * The field order and names (`allocBytes`, `durationMs`, `path`) after the timestamp are fixed
	 * by that script's `MEMPROF\|file\|(\d+)\|(.*)` regex and its k=v parsing; changing them breaks
	 * the harness silently rather than with an error, since unknown keys are ignored.
	 */
	fun fileLine(
		nowMs: Long,
		allocBytes: Long,
		durationMs: Long,
		path: String?,
		name: String,
		completed: Boolean,
	): String = "MEMPROF|file|$nowMs|allocBytes=$allocBytes|durationMs=$durationMs|path=$path|name=$name|completed=$completed"

	private fun detailsSuffix(details: Map<String, Long>): String = details.entries.joinToString("") { (key, value) -> "|$key=$value" }
}
