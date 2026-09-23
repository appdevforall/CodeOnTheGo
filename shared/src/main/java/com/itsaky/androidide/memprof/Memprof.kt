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
 * Profiling hooks for measuring memory and time across a project open.
 *
 * Every hook costs one null check unless a [MemprofSink] is installed, which only the profileable
 * build variant does, so the calls can stay in shared code that also ships in release builds.
 */
object Memprof {
	/** Where events go, or null to drop them. */
	@JvmStatic
	@Volatile
	var sink: MemprofSink? = null

	/** Runs [action] as the phase [title]; the phase ends if [action] returns and is abandoned if it throws. */
	inline fun <R> phase(
		title: String,
		marker: String,
		action: (MemprofSpan) -> R,
	): R = runIn(sink?.beginPhase(title, marker), action)

	/** Runs [action] as one instance of the repeated work [name], ended or abandoned like a [phase]. */
	inline fun <R> section(
		name: String,
		detail: String? = null,
		action: (MemprofSpan) -> R,
	): R = runIn(sink?.beginSection(name, detail), action)

	/**
	 * Starts a phase whose start and end happen in different callbacks.
	 *
	 * The caller owns the returned span and must end or abandon it on every path, abandoning it in a
	 * `finally` if nothing else did; use [phase] whenever the work fits one block.
	 */
	fun beginPhase(
		title: String,
		marker: String,
	): MemprofSpan = sink?.beginPhase(title, marker) ?: MemprofSpan.None

	/** Records that the instant named [marker] was reached. */
	fun mark(marker: String) {
		sink?.mark(marker)
	}

	@PublishedApi
	internal inline fun <R> runIn(
		span: MemprofSpan?,
		action: (MemprofSpan) -> R,
	): R {
		if (span == null) {
			return action(MemprofSpan.None)
		}

		/*
		 * action is not crossinline, so a return/break/continue out of the caller's block skips
		 * straight past the end() call below without throwing. finally still runs on that path,
		 * so abandon() always fires there; end() having already run on the normal path makes that
		 * abandon() a no-op, per MemprofSpan's own idempotency contract ("once a span has ended or
		 * been abandoned, further calls are ignored").
		 */
		try {
			val result = action(span)
			span.end()
			return result
		} finally {
			span.abandon()
		}
	}
}
