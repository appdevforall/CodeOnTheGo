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
 * One timed unit of work, started by [MemprofSink] and ended exactly once.
 *
 * Prefer [Memprof.phase] and [Memprof.section], which end the span for you. Hold a span directly
 * only when the work starts and ends in different callbacks.
 */
interface MemprofSpan {
	/** Whether anything records this span; false means every other member does nothing. */
	val isRecording: Boolean

	/** Attaches a numeric detail, reported with the span when it ends. */
	fun put(
		key: String,
		value: Long,
	)

	/** Ends the span as completed. Once a span has ended or been abandoned, further calls are ignored. */
	fun end()

	/**
	 * Ends the span as not completed, e.g. because its work threw or was stopped.
	 *
	 * An abandoned phase does not emit its marker, so nothing waiting for the marker mistakes an
	 * interrupted phase for a finished one.
	 */
	fun abandon()

	/** The span handed out when no sink is installed. */
	object None : MemprofSpan {
		override val isRecording: Boolean = false

		override fun put(
			key: String,
			value: Long,
		) = Unit

		override fun end() = Unit

		override fun abandon() = Unit
	}
}
