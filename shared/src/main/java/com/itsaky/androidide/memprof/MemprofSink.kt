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

/** Receives the events reported through [Memprof]; only the profileable variant installs one. */
interface MemprofSink {
	/**
	 * Starts a phase: a one-off stage of work, reported as a row of its own.
	 *
	 * @param title What a person reading a trace or report should see, e.g. "Parse project model".
	 * @param marker The machine name emitted when the phase ends, e.g. `cache_parsed`.
	 */
	fun beginPhase(
		title: String,
		marker: String,
	): MemprofSpan

	/**
	 * Starts a section: one instance of repeated work, reported in aggregate under [name].
	 *
	 * @param detail What distinguishes this instance, e.g. the file being indexed.
	 */
	fun beginSection(
		name: String,
		detail: String?,
	): MemprofSpan

	/** Records that the instant named [marker] was reached. */
	fun mark(marker: String)
}
